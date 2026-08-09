package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityEventRepository
        extends JpaRepository<SecurityEvent, String> {
}
