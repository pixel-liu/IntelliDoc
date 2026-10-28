package com.yizhaoqi.intellidoc.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件处理任务类，用于Kafka消息传递
 */
@Data
@NoArgsConstructor
public class FileProcessingTask {
    public static final String TASK_TYPE_UPLOAD_PROCESS = "UPLOAD_PROCESS";
    public static final String TASK_TYPE_REINDEX = "REINDEX";

    private String fileMd5; // 文件的 MD5 校验值
    private String filePath; // 文件存储路径
    private String fileName; // 文件名
    private String userId;   // 上传用户ID
    private String orgTag;   // 文件所属组织标签
    private boolean isPublic; // 文件是否公开
    private String taskType; // 任务类型
    private String requesterId; // 发起重试的用户
    private String taskId; // 持久化任务 ID，重复投递时保持不变

    public FileProcessingTask(String fileMd5, String filePath, String fileName, String userId,
                              String orgTag, boolean isPublic, String taskType, String requesterId) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.taskType = taskType;
        this.requesterId = requesterId;
    }
    
    /**
     * 向后兼容的构造函数
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = null;
        this.orgTag = "DEFAULT";
        this.isPublic = false;
        this.taskType = TASK_TYPE_UPLOAD_PROCESS;
        this.requesterId = null;
    }
}
