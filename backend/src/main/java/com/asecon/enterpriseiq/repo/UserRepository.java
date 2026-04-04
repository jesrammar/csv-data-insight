package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.User;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.EntityGraph;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "companies")
    @Query("select u from User u")
    List<User> findAllWithCompanies();

    @EntityGraph(attributePaths = "companies")
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdWithCompanies(@Param("id") Long id);

    @EntityGraph(attributePaths = "companies")
    @Query("select distinct u from User u join u.companies c where c.id in :companyIds")
    List<User> findDistinctByCompanyIdsWithCompanies(@Param("companyIds") Set<Long> companyIds);
}
