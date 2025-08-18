package com.LMS.LMS.Reppo;



import org.springframework.data.jpa.repository.JpaRepository;
import com.LMS.LMS.Model.Loan;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Integer> {
    Optional<Loan> findByAccountAccountNumber(String accountNumber);
    List<Loan> findByUserId(int userId);
}
