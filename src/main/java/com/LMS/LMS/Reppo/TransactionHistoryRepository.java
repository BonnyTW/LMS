package com.LMS.LMS.Reppo;



import org.springframework.data.jpa.repository.JpaRepository;
import com.LMS.LMS.Model.TransactionHistory;

import java.util.List;

public interface TransactionHistoryRepository extends JpaRepository<TransactionHistory, Integer> {
    List<TransactionHistory> findByAccountAccountNumber(String accountNumber);
}
