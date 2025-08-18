package com.LMS.LMS.Service;

import com.LMS.LMS.Client.BmsClient;
import com.LMS.LMS.Model.*;
import com.LMS.LMS.Reppo.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class LoanService {

    private final BmsClient bmsClient;
    private final BankAccountRepository bankAccountRepo;
    private final LoanApplicationRepository loanAppRepo;
    private final TransactionHistoryRepository transactionHistoryRepo;
    private final LoanRepository loanRepo;
    private final RepaymentRepository repaymentRepo;

    public LoanService(BmsClient bmsClient,
                       BankAccountRepository bankAccountRepo,
                       LoanApplicationRepository loanAppRepo,
                       TransactionHistoryRepository transactionHistoryRepo,
                       LoanRepository loanRepo,
                       RepaymentRepository repaymentRepo) {
        this.bmsClient = bmsClient;
        this.bankAccountRepo = bankAccountRepo;
        this.loanAppRepo = loanAppRepo;
        this.transactionHistoryRepo = transactionHistoryRepo;
        this.loanRepo = loanRepo;
        this.repaymentRepo = repaymentRepo;
    }

    /** SEND ACCOUNT TO BMS */
    public String sendAccountToBms(String accountNumber, Users user) {
        // 🔎 Step 1: Check if VERIFIED by another user
        List<BankAccount> existingAccounts = bankAccountRepo.findAllByAccountNumber(accountNumber);
        boolean verifiedByAnother = existingAccounts.stream()
                .anyMatch(acc -> "VERIFIED".equals(acc.getStatus()) && acc.getUser().getId() != user.getId());

        if (verifiedByAnother) {
            return "❌ This account number is already registered by another user.";
        }

        // 🔎 Step 2: Check if current user already has a BankAccount row
        Optional<BankAccount> existingAccountOpt = bankAccountRepo.findByUser(user);

        if (existingAccountOpt.isPresent()) {
            BankAccount acc = existingAccountOpt.get();

            // Already VERIFIED → block
            if ("VERIFIED".equals(acc.getStatus())) {
                return "✅ You already have a verified bank account. No need to send again.";
            }

            // Retry limit
            if (acc.getRetryCount() >= 2) {
                return "⛔ You have reached the maximum of 2 attempts. Please contact support.";
            }

            // Same account, still waiting
            if ("WAITING_VERIFICATION".equals(acc.getStatus()) &&
                acc.getAccountNumber().equals(accountNumber)) {
                acc.setRetryCount(acc.getRetryCount() + 1);
                bankAccountRepo.save(acc);
                return "❌ You already submitted this account number and it’s waiting for verification. Attempt #" 
                        + acc.getRetryCount();
            }

            // Update with new account
            String result = bmsClient.verifyAccount(accountNumber);
            if (result != null && result.toLowerCase().contains("micro deposit")) {
                acc.setAccountNumber(accountNumber);
                acc.setStatus("WAITING_VERIFICATION");
                acc.setRetryCount(acc.getRetryCount() + 1);
                bankAccountRepo.save(acc);
                return "🔄 Account updated, micro deposit sent again. Attempt #" + acc.getRetryCount();
            }
            return "BMS verification failed: " + result;
        }

        // 🔎 Step 3: Create new BankAccount
        String result = bmsClient.verifyAccount(accountNumber);
        if (result != null && result.toLowerCase().contains("micro deposit")) {
            BankAccount acc = new BankAccount();
            acc.setUser(user);
            acc.setAccountNumber(accountNumber);
            acc.setStatus("WAITING_VERIFICATION");
            acc.setRetryCount(1);
            bankAccountRepo.save(acc);
            return "✅ " + result + " Attempt #1";
        }

        return "BMS verification failed: " + result;
    }

    
    /** CONFIRM MICRO-DEPOSIT (SAFE & USER-SPECIFIC) */
    /** CONFIRM MICRO-DEPOSIT (SAFE & USER-SPECIFIC) */
    public ResponseEntity<String> confirmMicroDeposit(String accountNumber, BigDecimal amount, Users user) {
        String result = bmsClient.confirmMicroDeposit(accountNumber, amount);

        if (result != null && result.toLowerCase().contains("verified")) {
            // Get all accounts with this accountNumber
            List<BankAccount> accounts = bankAccountRepo.findAllByAccountNumber(accountNumber);

            // Find account owned by this user
            Optional<BankAccount> userAccountOpt = accounts.stream()
                    .filter(acc -> acc.getUser().getId() == user.getId())
                    .findFirst();

            if (userAccountOpt.isPresent()) {
                BankAccount mainAccount = userAccountOpt.get();
                mainAccount.setStatus("VERIFIED");
                bankAccountRepo.save(mainAccount);

                // Delete duplicates for other users
                accounts.stream()
                        .filter(acc -> acc.getUser().getId() != user.getId())
                        .forEach(acc -> bankAccountRepo.delete(acc));

                return ResponseEntity.ok("✅ Bank account verified successfully for your account.");
            } else {
                // Edge case: user never submitted this account → create
                BankAccount account = new BankAccount();
                account.setUser(user);
                account.setAccountNumber(accountNumber);
                account.setStatus("VERIFIED");
                bankAccountRepo.save(account);

                // Delete duplicates for other users
                accounts.stream()
                        .filter(acc -> acc.getUser().getId() != user.getId())
                        .forEach(acc -> bankAccountRepo.delete(acc));

                return ResponseEntity.ok("✅ Bank account verified and created for your account.");
            }
        }

        return ResponseEntity.badRequest().body("Micro deposit verification failed: " + result);
    }



    /** APPLY LOAN */
    public String applyLoan(String accountNumber, BigDecimal amount, String purpose, int termMonths, Users user) {
        // Get all accounts with this account number
        List<BankAccount> accounts = bankAccountRepo.findAllByAccountNumber(accountNumber);

        // Filter VERIFIED account that belongs to current user
        Optional<BankAccount> accountOpt = accounts.stream()
                .filter(acc -> "VERIFIED".equals(acc.getStatus()) && acc.getUser().getId() == user.getId())
                .findFirst();

        if (accountOpt.isEmpty()) {
            return "❌ Account not verified or does not belong to you!";
        }

        BankAccount account = accountOpt.get();

        LoanApplication app = new LoanApplication();
        app.setUser(user);
        app.setAccount(account);
        app.setLoanAmount(amount);
        app.setPurpose(purpose);
        app.setTermMonths(termMonths);
        app.setStatus("PENDING");
        loanAppRepo.save(app);

        try {
            List<TransactionHistory> transactions = bmsClient.getTransactionHistory(accountNumber);
            for (TransactionHistory t : transactions) {
                t.setAccount(account);
            }
            if (!transactions.isEmpty()) transactionHistoryRepo.saveAll(transactions);
        } catch (Exception ignore) {}

        return "✅ Loan application submitted successfully.";
    }


    /** APPROVE LOAN */
    public String approveLoan(int loanApplicationId) {
        Optional<LoanApplication> appOpt = loanAppRepo.findById(loanApplicationId);
        if (appOpt.isEmpty()) return "Loan application not found.";

        LoanApplication app = appOpt.get();
        app.setStatus("APPROVED");
        loanAppRepo.save(app);

        String disburseResult = bmsClient.disburseLoan(app.getAccount().getAccountNumber(), app.getLoanAmount());

        if (disburseResult != null && disburseResult.toLowerCase().contains("loan of $")) {
            Loan loan = new Loan();
            loan.setUser(app.getUser());
            loan.setAccount(app.getAccount());
            loan.setTotalLoan(app.getLoanAmount());
            loan.setRemainingAmount(app.getLoanAmount());
            loan.setLoanDate(new Date());
            loan.setDueDate(new Date(System.currentTimeMillis() + app.getTermMonths() * 30L * 24 * 3600 * 1000));
            loanRepo.save(loan);
        }

        return disburseResult;
    }

    /** REPAY LOAN */
    /** REPAY LOAN - user-specific */
    public ResponseEntity<String> repayLoan(String accountNumber, BigDecimal amount, Users user) {
        // Find loan by account number
        Optional<Loan> loanOpt = loanRepo.findByAccountAccountNumber(accountNumber);
        if (loanOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Loan not found.");
        }

        Loan loan = loanOpt.get();

        // Check ownership
        if (loan.getUser().getId() != user.getId()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("❌ You are not authorized to repay this loan.");
        }

        // Create repayment record
        Repayment repayment = new Repayment();
        repayment.setLoan(loan);
        repayment.setAmount(amount);
        repayment.setRepaymentDate(new Date());
        repayment.setStatus("WAITING");
        repaymentRepo.save(repayment);

        // Call BMS to process repayment
        String bmsResult = bmsClient.repayLoan(accountNumber, amount);
        if (bmsResult != null && bmsResult.toLowerCase().contains("received")) {
            repayment.setStatus("PAID");
            repaymentRepo.save(repayment);

            loan.setRemainingAmount(loan.getRemainingAmount().subtract(amount));
            if (loan.getRemainingAmount().signum() < 0) {
                loan.setRemainingAmount(BigDecimal.ZERO);
            }
            loanRepo.save(loan);
        } else {
            repayment.setStatus("FAILED");
            repaymentRepo.save(repayment);
        }

        return ResponseEntity.ok(bmsResult);
    }

}
