package com.yizhaoqi.intellidoc.repository;

import com.yizhaoqi.intellidoc.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
class DocumentIsolationRepositoryTest {
    @Autowired FileUploadRepository files;
    @Autowired DocumentVectorRepository vectors;

    @Test
    void sameContentHasIndependentUserReferencesAndVectorRows() {
        String md5 = "0123456789abcdef0123456789abcdef";
        for (String user : new String[]{"1", "2"}) {
            FileUpload file = new FileUpload(); file.setFileMd5(md5); file.setUserId(user);
            file.setFileName("same.pdf"); files.saveAndFlush(file);
            DocumentVector vector = new DocumentVector(); vector.setFileMd5(md5); vector.setUserId(user);
            vector.setChunkId(0); vector.setTextContent("content for " + user); vectors.saveAndFlush(vector);
        }
        assertEquals(2, files.findAllByFileMd5ForUpdate(md5).size());
        vectors.deleteByFileMd5AndUserId(md5, "1");
        files.deleteByFileMd5AndUserId(md5, "1");
        files.flush(); vectors.flush();
        assertEquals(0, files.countByFileMd5AndUserId(md5, "1"));
        assertEquals(1, files.countByFileMd5AndUserId(md5, "2"));
        assertTrue(vectors.findByFileMd5AndUserIdOrderByChunkIdAsc(md5, "1").isEmpty());
        assertEquals("content for 2", vectors.findByFileMd5AndUserIdOrderByChunkIdAsc(md5, "2").get(0).getTextContent());
    }
}
