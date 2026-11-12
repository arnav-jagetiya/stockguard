package com.stockguard.security.repository;

import com.stockguard.security.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findBySessionIdAndConsumedAtIsNull(UUID sessionId);

    List<RefreshToken> findAllBySessionIdOrderBySequenceNumberAsc(UUID sessionId);
}
