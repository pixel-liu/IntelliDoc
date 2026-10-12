package com.yizhaoqi.intellidoc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在问题进入 RAG 检索前，解析最近对话中的短距离指代。
 * 实体库按 conversation 隔离并存入 Redis，只保留最近五个用户轮次。
 */
@Service
public class CoreferenceResolutionService {

    private static final Logger logger = LoggerFactory.getLogger(CoreferenceResolutionService.class);
    private static final int WINDOW_USER_TURNS = 5;
    private static final int MAX_ENTITY_MEMORY_SIZE = 40;
    private static final Duration ENTITY_MEMORY_TTL = Duration.ofDays(7);

    private static final Pattern QUOTED_ENTITY_PATTERN = Pattern.compile(
            "[\\u201c\\u2018\\\"'\\u300a]([^\\u201d\\u2019\\\"'\\u300b\\r\\n]{2,40})[\\u201d\\u2019\\\"'\\u300b]");
    private static final Pattern PREFIX_TOPIC_PATTERN = Pattern.compile(
            "(?:什么是|何为|介绍(?:一下|下)?|解释(?:一下|下)?|了解(?:一下|下)?|谈谈|关于)\\s*"
                    + "([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9._+#\\-]{1,39}?)(?=[，。；！？!?]|$)");
    private static final Pattern SUFFIX_TOPIC_PATTERN = Pattern.compile(
            "([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9._+#\\-]{1,39}?)(?=是什么|是做什么的|有哪些|有什么|如何|怎么|是否)");
    private static final Pattern LATIN_ENTITY_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9._+#\\-]{1,31})(?![A-Za-z0-9])");

    private static final List<ReferenceRule> REFERENCE_RULES = List.of(
            new ReferenceRule(Pattern.compile("该公司|该企业|该机构|该组织|这家公司|这个企业|这个机构|这个组织"), EntityType.ORGANIZATION, false),
            new ReferenceRule(Pattern.compile("这里|那里|该地|此地|上述地点|前述地点"), EntityType.LOCATION, false),
            new ReferenceRule(Pattern.compile("他们的|她们的|他的|她的"), EntityType.PERSON, true),
            new ReferenceRule(Pattern.compile("(?<!其)他们|她们|(?<!其)他(?!们|人|方)|她(?!们|人|方)"), EntityType.PERSON, false),
            new ReferenceRule(Pattern.compile("它的|其(?!他|实|中|次|后|前)"), null, true),
            new ReferenceRule(Pattern.compile("该系统|该技术|该方法|该概念|该产品|该项目|该功能|"
                    + "这个系统|这个技术|这个方法|这个概念|这个产品|这个项目|这个功能|"
                    + "上述(?:系统|技术|方法|概念|产品|项目|功能)?|前述(?:系统|技术|方法|概念|产品|项目|功能)?|"
                    + "它们|它|这个|那个|这些|那些"), null, false)
    );

    private static final Set<String> CONCEPT_STOP_WORDS = Set.of(
            "什么", "哪些", "哪个", "怎么", "如何", "是否", "可以", "能够", "需要", "请问", "一下",
            "应用", "功能", "作用", "优点", "缺点", "特点", "原理", "方法", "方式", "问题", "内容",
            "信息", "资料", "文档", "回答", "结果", "方面", "场景", "例子", "示例", "区别", "联系",
            "关系", "流程", "步骤", "原因", "意义", "概念", "系统", "技术", "产品", "项目", "公司",
            "企业", "组织", "机构", "地方", "地点", "用户", "知识库", "对话", "消息", "情况", "东西",
            "这个", "那个", "这些", "那些", "上述", "前述", "它", "其", "他们", "她们"
    );
    private static final Set<String> LATIN_STOP_WORDS = Set.of(
            "what", "which", "how", "the", "this", "that", "it", "its", "and", "or", "is", "are",
            "can", "could", "would", "should", "please"
    );
    private static final Set<String> CONCEPT_HEAD_WORDS = Set.of(
            "学习", "算法", "模型", "系统", "平台", "框架", "技术", "方法", "协议", "数据库", "引擎", "服务", "工具"
    );

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public CoreferenceResolutionService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public ResolutionResult resolve(String conversationId,
                                    String userMessage,
                                    List<Map<String, String>> conversationHistory) {
        if (userMessage == null || userMessage.isBlank()) {
            return new ResolutionResult(userMessage, userMessage, List.of(), List.of());
        }

        List<EntityMention> candidates = collectCandidates(conversationId, userMessage, conversationHistory);
        String resolvedMessage = userMessage;
        List<ResolvedReference> resolvedReferences = new ArrayList<>();

        for (ReferenceRule rule : REFERENCE_RULES) {
            Matcher matcher = rule.pattern().matcher(resolvedMessage);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                EntityMention antecedent = nearestCompatibleEntity(candidates, rule.expectedType());
                if (antecedent == null) {
                    continue;
                }
                String replacement = antecedent.text() + (rule.possessive() ? "的" : "");
                resolvedReferences.add(new ResolvedReference(matcher.group(), antecedent.text(), antecedent.type()));
                matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(output);
            resolvedMessage = output.toString();
        }

        List<EntityMention> entityMemory = refreshEntityMemory(
                conversationId, resolvedMessage, conversationHistory, candidates);
        return new ResolutionResult(userMessage, resolvedMessage, List.copyOf(resolvedReferences), entityMemory);
    }

    private List<EntityMention> collectCandidates(String conversationId,
                                                  String userMessage,
                                                  List<Map<String, String>> conversationHistory) {
        List<EntityMention> candidates = new ArrayList<>();
        int firstReferencePosition = firstReferencePosition(userMessage);
        if (firstReferencePosition > 0) {
            candidates.addAll(withTurnDistance(extractEntities(userMessage.substring(0, firstReferencePosition)), 0));
        }
        candidates.addAll(extractRecentHistoryEntities(conversationHistory));
        for (EntityMention stored : loadEntityMemory(conversationId)) {
            int agedDistance = stored.turnDistance() + 1;
            if (agedDistance <= WINDOW_USER_TURNS) {
                candidates.add(new EntityMention(stored.text(), stored.type(), agedDistance, stored.position()));
            }
        }
        return deduplicateAndSort(candidates);
    }

    private List<EntityMention> refreshEntityMemory(String conversationId,
                                                    String resolvedMessage,
                                                    List<Map<String, String>> conversationHistory,
                                                    List<EntityMention> fallbackCandidates) {
        List<EntityMention> refreshed = new ArrayList<>();
        refreshed.addAll(withTurnDistance(extractEntities(resolvedMessage), 0));
        refreshed.addAll(extractRecentHistoryEntities(conversationHistory));
        refreshed.addAll(fallbackCandidates);
        List<EntityMention> memory = deduplicateAndSort(refreshed);
        if (memory.size() > MAX_ENTITY_MEMORY_SIZE) {
            memory = new ArrayList<>(memory.subList(0, MAX_ENTITY_MEMORY_SIZE));
        }
        saveEntityMemory(conversationId, memory);
        return List.copyOf(memory);
    }

    private List<EntityMention> extractRecentHistoryEntities(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<EntityMention> result = new ArrayList<>();
        int userTurnDistance = 0;
        for (int index = history.size() - 1; index >= 0 && userTurnDistance < WINDOW_USER_TURNS; index--) {
            Map<String, String> message = history.get(index);
            if (!"user".equals(message.get("role"))) {
                continue;
            }
            userTurnDistance++;
            result.addAll(withTurnDistance(extractEntities(message.get("content")), userTurnDistance));
        }
        return result;
    }

    List<EntityMention> extractEntities(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, EntityMention> entities = new LinkedHashMap<>();
        collectPatternEntities(text, QUOTED_ENTITY_PATTERN, entities);
        collectPatternEntities(text, PREFIX_TOPIC_PATTERN, entities);
        collectPatternEntities(text, SUFFIX_TOPIC_PATTERN, entities);

        Matcher latinMatcher = LATIN_ENTITY_PATTERN.matcher(text);
        while (latinMatcher.find()) {
            String candidate = latinMatcher.group(1);
            if (!LATIN_STOP_WORDS.contains(candidate.toLowerCase(Locale.ROOT))) {
                addEntity(entities, candidate, EntityType.CONCEPT, latinMatcher.end(1));
            }
        }

        try {
            List<Term> terms = StandardTokenizer.segment(text);
            int searchFrom = 0;
            for (int index = 0; index < terms.size(); index++) {
                Term term = terms.get(index);
                String word = normalizeCandidate(term.word);
                if (word == null) {
                    continue;
                }
                int start = text.indexOf(word, searchFrom);
                if (start < 0) {
                    start = text.indexOf(word);
                }
                start = Math.max(start, 0);
                searchFrom = start + word.length();
                EntityType type = entityTypeFromNature(term.nature == null ? "" : term.nature.toString());
                if (type != null && (type != EntityType.CONCEPT || !isIgnoredConcept(word))) {
                    addEntity(entities, word, type, searchFrom);
                }
                if (type == EntityType.CONCEPT && index + 1 < terms.size()) {
                    String nextWord = normalizeCandidate(terms.get(index + 1).word);
                    if (nextWord != null && CONCEPT_HEAD_WORDS.contains(nextWord)) {
                        addEntity(entities, word + nextWord, EntityType.CONCEPT,
                                searchFrom + nextWord.length());
                    }
                }
            }
        } catch (Exception exception) {
            logger.debug("HanLP 实体提取失败，保留正则提取结果: {}", exception.getMessage());
        }
        return new ArrayList<>(entities.values());
    }

    private void collectPatternEntities(String text, Pattern pattern, Map<String, EntityMention> entities) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String candidate = normalizeCandidate(matcher.group(1));
            if (candidate == null || containsCoordinator(candidate) || isIgnoredConcept(candidate)) {
                continue;
            }
            addEntity(entities, candidate, inferEntityType(candidate), matcher.end(1));
        }
    }

    private EntityType inferEntityType(String candidate) {
        try {
            List<Term> terms = StandardTokenizer.segment(candidate);
            EntityType namedEntityType = null;
            for (Term term : terms) {
                EntityType type = entityTypeFromNature(
                        term.nature == null ? "" : term.nature.toString());
                if (type == EntityType.ORGANIZATION) {
                    return EntityType.ORGANIZATION;
                }
                if (type == EntityType.LOCATION || type == EntityType.PERSON) {
                    namedEntityType = type;
                }
            }
            if (namedEntityType != null) {
                return namedEntityType;
            }
        } catch (Exception ignored) {
            // 正则提取的主题无法分类时按通用概念处理。
        }
        return EntityType.CONCEPT;
    }

    private EntityType entityTypeFromNature(String nature) {
        if (nature.startsWith("nr")) {
            return EntityType.PERSON;
        }
        if (nature.startsWith("ns")) {
            return EntityType.LOCATION;
        }
        if (nature.startsWith("nt")) {
            return EntityType.ORGANIZATION;
        }
        if (nature.startsWith("nz") || nature.startsWith("nx") || "n".equals(nature)
                || nature.startsWith("vn") || nature.startsWith("nv")) {
            return EntityType.CONCEPT;
        }
        return null;
    }

    private void addEntity(Map<String, EntityMention> entities, String text, EntityType type, int position) {
        String normalized = normalizeCandidate(text);
        if (normalized == null) {
            return;
        }
        String key = normalized.toLowerCase(Locale.ROOT) + "|" + type;
        EntityMention existing = entities.get(key);
        if (existing == null || position > existing.position()) {
            entities.put(key, new EntityMention(normalized, type, 0, position));
        }
    }

    private String normalizeCandidate(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim()
                .replaceFirst("^(?:请问|请介绍|请解释|我想了解|想了解)", "")
                .replaceFirst("(?:的相关信息|的情况|相关信息)$", "")
                .trim();
        if (normalized.length() < 2 || normalized.length() > 40 || normalized.matches("[\\p{Punct}\\s]+")) {
            return null;
        }
        return normalized;
    }

    private boolean isIgnoredConcept(String value) {
        return CONCEPT_STOP_WORDS.contains(value)
                || value.matches("[0-9.]+")
                || value.matches("(?:这|那|该|每|各|某).*");
    }

    private boolean containsCoordinator(String value) {
        return value.contains("和") || value.contains("与") || value.contains("及") || value.contains("、");
    }

    private int firstReferencePosition(String message) {
        int earliest = Integer.MAX_VALUE;
        for (ReferenceRule rule : REFERENCE_RULES) {
            Matcher matcher = rule.pattern().matcher(message);
            if (matcher.find()) {
                earliest = Math.min(earliest, matcher.start());
            }
        }
        return earliest == Integer.MAX_VALUE ? -1 : earliest;
    }

    private EntityMention nearestCompatibleEntity(List<EntityMention> candidates, EntityType expectedType) {
        for (EntityMention candidate : candidates) {
            if (expectedType == null || candidate.type() == expectedType) {
                return candidate;
            }
        }
        return null;
    }

    private List<EntityMention> withTurnDistance(List<EntityMention> entities, int turnDistance) {
        return entities.stream()
                .map(entity -> new EntityMention(entity.text(), entity.type(), turnDistance, entity.position()))
                .toList();
    }

    private List<EntityMention> deduplicateAndSort(List<EntityMention> entities) {
        List<EntityMention> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparingInt(EntityMention::turnDistance)
                .thenComparing(Comparator.comparingInt(EntityMention::position).reversed()));
        Set<String> seen = new HashSet<>();
        List<EntityMention> result = new ArrayList<>();
        for (EntityMention entity : sorted) {
            if (entity.turnDistance() > WINDOW_USER_TURNS) {
                continue;
            }
            String key = entity.text().toLowerCase(Locale.ROOT) + "|" + entity.type();
            if (seen.add(key)) {
                result.add(entity);
            }
        }
        return result;
    }

    private List<EntityMention> loadEntityMemory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        try {
            String json = redisTemplate.opsForValue().get(entityKey(conversationId));
            return json == null || json.isBlank()
                    ? List.of()
                    : objectMapper.readValue(json, new TypeReference<List<EntityMention>>() { });
        } catch (Exception exception) {
            logger.warn("读取会话实体库失败，将从最近对话重建: conversationId={}", conversationId, exception);
            return List.of();
        }
    }

    private void saveEntityMemory(String conversationId, List<EntityMention> entities) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    entityKey(conversationId), objectMapper.writeValueAsString(entities), ENTITY_MEMORY_TTL);
        } catch (Exception exception) {
            logger.warn("保存会话实体库失败，本轮继续使用内存实体: conversationId={}", conversationId, exception);
        }
    }

    private String entityKey(String conversationId) {
        return "conversation:" + conversationId + ":entities";
    }

    public enum EntityType {
        PERSON, LOCATION, ORGANIZATION, CONCEPT
    }

    public record EntityMention(String text, EntityType type, int turnDistance, int position) {
    }

    public record ResolvedReference(String reference, String antecedent, EntityType entityType) {
    }

    public record ResolutionResult(String originalMessage,
                                   String resolvedMessage,
                                   List<ResolvedReference> resolvedReferences,
                                   List<EntityMention> entityMemory) {
        public boolean changed() {
            return originalMessage != null && !originalMessage.equals(resolvedMessage);
        }
    }

    private record ReferenceRule(Pattern pattern, EntityType expectedType, boolean possessive) {
    }
}
