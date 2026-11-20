package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface PromoSupplierCompensationRepository extends JpaRepository<PromoSupplierCompensation, Long> {
    List<PromoSupplierCompensation> findByBusinessDateBetween(LocalDate from, LocalDate to);
    List<PromoSupplierCompensation> findAllByOrderByBusinessDateAsc();
}
