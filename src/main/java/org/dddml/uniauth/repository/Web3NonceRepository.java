package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.Web3Nonce;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface Web3NonceRepository extends JpaRepository<Web3Nonce, String> {

    Optional<Web3Nonce> findByWalletAddress(String walletAddress);

    void deleteByWalletAddress(String walletAddress);
}
