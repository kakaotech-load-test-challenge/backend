package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.File;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileRepository extends MongoRepository<File, String> {
    // 필요 시 사용자별 조회 기능 추가 가능
    // List<File> findByUserId(String userId);
}