package com.yizhaoqi.intellidoc.repository;

import com.yizhaoqi.intellidoc.model.RateLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, String> {
}
