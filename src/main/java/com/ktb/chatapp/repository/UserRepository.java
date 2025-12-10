package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    // MessageLoader 성능 최적화를 위해 추가
    List<User> findByIdIn(Collection<String> ids);
}