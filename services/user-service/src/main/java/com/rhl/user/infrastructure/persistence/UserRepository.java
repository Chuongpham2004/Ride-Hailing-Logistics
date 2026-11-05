package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    /**
     * Administrator lookup: every filter optional, newest first, keyset pagination on the UUIDv7
     * id. {@code text} is a lower-case LIKE pattern for email and name (wildcards escaped with '!');
     * {@code phone} a LIKE pattern of digits for the E.164 phone.
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE (CAST(:id AS UUID) IS NULL OR u.id = CAST(:id AS UUID))
              AND (CAST(:text AS VARCHAR) IS NULL
                   OR lower(u.email) LIKE CAST(:text AS VARCHAR) ESCAPE '!'
                   OR lower(u.full_name) LIKE CAST(:text AS VARCHAR) ESCAPE '!'
                   OR (CAST(:phone AS VARCHAR) IS NOT NULL AND u.phone LIKE CAST(:phone AS VARCHAR)))
              AND (CAST(:status AS VARCHAR) IS NULL OR u.status = CAST(:status AS VARCHAR))
              AND (CAST(:role AS VARCHAR) IS NULL OR EXISTS (
                   SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = CAST(:role AS VARCHAR)))
              AND (CAST(:before AS UUID) IS NULL OR u.id < CAST(:before AS UUID))
            ORDER BY u.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> search(@Param("id") UUID id, @Param("text") String text, @Param("phone") String phone,
                      @Param("status") String status, @Param("role") String role, @Param("before") UUID before,
                      @Param("limit") int limit);
}
