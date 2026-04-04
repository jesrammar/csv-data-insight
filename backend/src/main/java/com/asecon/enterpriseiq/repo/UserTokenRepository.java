package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.UserToken;
import com.asecon.enterpriseiq.model.UserTokenPurpose;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {
    @Query("""
        select t from UserToken t
        join fetch t.user u
        where t.tokenHash = :hash
          and t.purpose = :purpose
          and t.usedAt is null
          and t.expiresAt > :now
        """)
    Optional<UserToken> findValidByHashWithUser(@Param("hash") String hash,
                                                @Param("purpose") UserTokenPurpose purpose,
                                                @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
        update UserToken t
        set t.usedAt = :now
        where t.user.id = :userId
          and t.purpose = :purpose
          and t.usedAt is null
        """)
    int invalidateActiveForUser(@Param("userId") Long userId,
                                @Param("purpose") UserTokenPurpose purpose,
                                @Param("now") Instant now);
}

