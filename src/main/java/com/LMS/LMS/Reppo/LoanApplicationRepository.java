package com.LMS.LMS.Reppo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.LMS.LMS.Model.LoanApplication;

import java.util.List;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Integer> {
    List<LoanApplication> findByStatus(String status);
    List<LoanApplication> findByAccountAccountNumber(String accountNumber);
}
