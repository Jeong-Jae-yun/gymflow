package com.gymflow.domain.user.domain.repository;

import com.gymflow.domain.user.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
    boolean existsByEmailAndDeletedAtIsNull(String email);
}
