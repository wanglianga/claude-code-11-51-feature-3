package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PromoCostVersionRepository extends JpaRepository<PromoCostVersion, Long> {
    Optional<PromoCostVersion> findFirstByOrderByVersionDesc();
    List<PromoCostVersion> findAllByOrderByVersionDesc();
    Optional<PromoCostVersion> findByVersion(Integer version);
}
