package com.LMS.LMS.Service;

import com.LMS.LMS.Client.BmsClient;
import com.LMS.LMS.DTO.LoanApplicationDto;
import com.LMS.LMS.DTO.LoanSummaryDto;
import com.LMS.LMS.DTO.PendingLoanResponseDto;
import com.LMS.LMS.Model.*;
import com.LMS.LMS.Reppo.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class LoanService {

    @Autowired
    LoanEmiScheduleRepository loanEmiScheduleRepo;
    
    @Autowired
    private MailService mailService;


    private final BmsClient bmsClient;
    private final BankAccountRepository bankAccountRepo;
    private final LoanApplicationRepository loanAppRepo;
    private final LoanRepository loanRepo;
    private final RepaymentRepository repaymentRepo;

    public LoanService(BmsClient bmsClient,
                       BankAccountRepository bankAccountRepo,
                       LoanApplicationRepository loanAppRepo,
                       LoanRepository loanRepo,
                       RepaymentRepository repaymentRepo) {
        this.bmsClient = bmsClient;
        this.bankAccountRepo = bankAccountRepo;
        this.loanAppRepo = loanAppRepo;
        
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
        Optional<BankAccount> accountOpt = bankAccountRepo.findAllByAccountNumber(accountNumber).stream()
                .filter(acc -> "VERIFIED".equals(acc.getStatus()) && Objects.equals(acc.getUser().getId(), user.getId()))
                .findFirst();
        if (accountOpt.isEmpty()) return "❌ Account not verified or not yours.";

        LoanSummaryDto summary = bmsClient.getLoanSummary(accountNumber);
        if (summary.getLoanRemaining().compareTo(BigDecimal.ZERO) > 0)
            return "❌ Cannot apply: outstanding loan " + summary.getLoanRemaining();

        // 👉 EMI Calculation
        BigDecimal annualInterestRate = new BigDecimal("10"); // 10% annual
        BigDecimal monthlyRate = annualInterestRate.divide(BigDecimal.valueOf(12 * 100), 10, RoundingMode.HALF_UP);

        int n = termMonths;
        BigDecimal P = amount;

        // EMI = P * r * (1+r)^n / ((1+r)^n -1)
        BigDecimal onePlusRPowerN = (BigDecimal.ONE.add(monthlyRate)).pow(n);
        BigDecimal emi = P.multiply(monthlyRate).multiply(onePlusRPowerN)
                .divide(onePlusRPowerN.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);

        BigDecimal totalPayable = emi.multiply(BigDecimal.valueOf(n)).setScale(2, RoundingMode.HALF_UP);

        // 👉 Save Loan Application
        LoanApplication app = new LoanApplication();
        app.setUser(user);
        app.setAccount(accountOpt.get());
        app.setLoanAmount(amount);
        app.setPurpose(purpose);
        app.setTermMonths(termMonths);
        app.setStatus("PENDING");
        app.setEmiPerMonth(emi);
        app.setTotalEmi(totalPayable);

        loanAppRepo.save(app);

        return "✅ Loan application submitted successfully. " +
                "\nMonthly EMI: " + emi + 
                "\nTotal Payable: " + totalPayable;
    }


    /** GET PENDING LOANS FOR ADMIN */
    public List<PendingLoanResponseDto> getPendingLoansForAdmin() {
        List<LoanApplication> pendingApps = loanAppRepo.findAll().stream()
                .filter(app -> "PENDING".equals(app.getStatus()))
                .toList();

        List<PendingLoanResponseDto> response = new ArrayList<>();
        for (LoanApplication app : pendingApps) {
            PendingLoanResponseDto dto = new PendingLoanResponseDto();
            LoanApplicationDto appDto = new LoanApplicationDto();
            appDto.setId(app.getId());
            appDto.setLoanAmount(app.getLoanAmount());
            appDto.setPurpose(app.getPurpose());
            appDto.setStatus(app.getStatus());
            dto.setApplication(appDto);
            LoanSummaryDto summaryDto = bmsClient.getLoanSummary(app.getAccount().getAccountNumber());
            dto.setLoanSummary(summaryDto);
            response.add(dto);
        }
        return response;
    }

    /** APPROVE LOAN */
    public String approveLoan(int loanApplicationId) {
        Optional<LoanApplication> appOpt = loanAppRepo.findById(loanApplicationId);
        if (appOpt.isEmpty()) return "❌ Loan application not found.";

        LoanApplication app = appOpt.get();
        LoanSummaryDto summary = bmsClient.getLoanSummary(app.getAccount().getAccountNumber());

        // If user already has outstanding loan -> reject
        if (summary.getLoanRemaining().compareTo(BigDecimal.ZERO) > 0) {
            app.setStatus("REJECTED");
            loanAppRepo.save(app);

            mailService.sendEmail(
                app.getUser().getEmail(),
                "Loan Application Rejected",
                "Hello " + app.getUser().getUsername() + 
                ",\n\nYour loan application has been rejected due to outstanding loan balance of " 
                + summary.getLoanRemaining() + "."
            );

            return "❌ Cannot approve: outstanding loan " + summary.getLoanRemaining();
        }

        // Otherwise approve loan
        app.setStatus("APPROVED");
        loanAppRepo.save(app);

        String disburseResult = bmsClient.disburseLoan(app.getAccount().getAccountNumber(), app.getLoanAmount());
        if (disburseResult != null && disburseResult.toLowerCase().contains("loan of $")) {
            Loan loan = new Loan();
            loan.setUser(app.getUser());
            loan.setAccount(app.getAccount());
            loan.setTotalLoan(app.getLoanAmount());
            loan.setRemainingAmount(app.getLoanAmount());
            loan.setTermMonths(app.getTermMonths());
            loan.setLoanDate(new Date());
            loan.setDueDate(new Date(System.currentTimeMillis() + app.getTermMonths() * 30L * 24 * 3600 * 1000));
            loanRepo.save(loan);

            BigDecimal annualInterestRate = new BigDecimal("10"); // 10% annual interest
            generateEmiSchedule(loan, annualInterestRate);
        }

        // Send approval email
        mailService.sendEmail(
            app.getUser().getEmail(),
            "Loan Application Approved",
            "Hello " + app.getUser().getUsername() + 
            ",\n\nCongratulations! Your loan of " + app.getLoanAmount() + " has been approved and disbursed."
        );

        return disburseResult;
    }



    /** GENERATE EMI SCHEDULE */
    public void generateEmiSchedule(Loan loan, BigDecimal annualInterestRate) {
        int n = loan.getTermMonths();
        BigDecimal P = loan.getTotalLoan();
        BigDecimal monthlyRate = annualInterestRate.divide(BigDecimal.valueOf(12 * 100), 10, RoundingMode.HALF_UP);

        // EMI = P * r * (1+r)^n / ((1+r)^n -1)
        BigDecimal onePlusRPowerN = (BigDecimal.ONE.add(monthlyRate)).pow(n);
        BigDecimal emi = P.multiply(monthlyRate).multiply(onePlusRPowerN)
                .divide(onePlusRPowerN.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);

        loan.setEmiAmount(emi);
        loanRepo.save(loan);

        BigDecimal remainingPrincipal = P;
        Calendar cal = Calendar.getInstance();
        cal.setTime(loan.getLoanDate());

        for (int i = 1; i <= n; i++) {
            BigDecimal interestComponent = remainingPrincipal.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalComponent = emi.subtract(interestComponent).setScale(2, RoundingMode.HALF_UP);

            // Adjust last EMI principal to avoid rounding issues
            if (i == n) {
                principalComponent = remainingPrincipal;
                emi = principalComponent.add(interestComponent);
                remainingPrincipal = BigDecimal.ZERO;
            } else {
                remainingPrincipal = remainingPrincipal.subtract(principalComponent).setScale(2, RoundingMode.HALF_UP);
            }

            LoanEmiSchedule schedule = new LoanEmiSchedule();
            schedule.setLoan(loan);
            schedule.setInstallmentNumber(i);
            schedule.setEmiAmount(emi);
            schedule.setPrincipalComponent(principalComponent);
            schedule.setInterestComponent(interestComponent);
            schedule.setRemainingPrincipal(remainingPrincipal);
            schedule.setStatus("PENDING");

            cal.add(Calendar.MONTH, 1);
            schedule.setDueDate(cal.getTime());

            loanEmiScheduleRepo.save(schedule);
        }
    }

    /** REPAY LOAN */
    public ResponseEntity<String> repayLoan(String accountNumber, BigDecimal amount, Users user) {
        Optional<Loan> loanOpt = loanRepo.findByAccountAccountNumber(accountNumber);
        if (loanOpt.isEmpty()) return ResponseEntity.badRequest().body("Loan not found.");

        Loan loan = loanOpt.get();
        if (!Integer.valueOf(loan.getUser().getId()).equals(user.getId()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("❌ You are not authorized.");

        // Save repayment record
        Repayment repayment = new Repayment();
        repayment.setLoan(loan);
        repayment.setAmount(amount);
        repayment.setRepaymentDate(new Date());
        repayment.setStatus("WAITING");
        repaymentRepo.save(repayment);

        // Simulate BMS payment
        String bmsResult = bmsClient.repayLoan(accountNumber, amount);
        if (bmsResult != null && bmsResult.toLowerCase().contains("received")) {
            repayment.setStatus("PAID");
            repaymentRepo.save(repayment);

            BigDecimal remainingPayment = amount;
            List<LoanEmiSchedule> schedules = loanEmiScheduleRepo.findByLoanOrderByInstallmentNumberAsc(loan);

            // Step 1: Deduct payment from loan.remainingAmount
            loan.setRemainingAmount(loan.getRemainingAmount().subtract(amount));
            if (loan.getRemainingAmount().compareTo(BigDecimal.ZERO) < 0) loan.setRemainingAmount(BigDecimal.ZERO);

            // Step 2: Pay EMIs interest first, then principal
            for (LoanEmiSchedule emi : schedules) {
                if ("PAID".equals(emi.getStatus())) continue;

                BigDecimal interest = emi.getInterestComponent();
                BigDecimal principal = emi.getPrincipalComponent();

                // Pay interest
                if (remainingPayment.compareTo(interest) >= 0) {
                    remainingPayment = remainingPayment.subtract(interest);
                    emi.setInterestComponent(BigDecimal.ZERO);

                    // Pay principal
                    if (remainingPayment.compareTo(principal) >= 0) {
                        remainingPayment = remainingPayment.subtract(principal);
                        emi.setPrincipalComponent(BigDecimal.ZERO);
                        emi.setRemainingPrincipal(BigDecimal.ZERO);
                        emi.setStatus("PAID");
                    } else {
                        emi.setPrincipalComponent(principal.subtract(remainingPayment));
                        emi.setRemainingPrincipal(emi.getRemainingPrincipal().subtract(remainingPayment));
                        remainingPayment = BigDecimal.ZERO;
                    }
                } else {
                    emi.setInterestComponent(interest.subtract(remainingPayment));
                    remainingPayment = BigDecimal.ZERO;
                }

                loanEmiScheduleRepo.save(emi);
                if (remainingPayment.compareTo(BigDecimal.ZERO) <= 0) break;
            }

            // Step 3: Recalculate future EMIs if loan is still outstanding
            BigDecimal remainingPrincipal = loan.getRemainingAmount();
            if (remainingPrincipal.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal monthlyRate = new BigDecimal("0.10").divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
                int remainingMonths = (int) schedules.stream().filter(e -> !"PAID".equals(e.getStatus())).count();

                BigDecimal onePlusRPowerN = (BigDecimal.ONE.add(monthlyRate)).pow(remainingMonths);
                BigDecimal newEmi = remainingPrincipal.multiply(monthlyRate).multiply(onePlusRPowerN)
                        .divide(onePlusRPowerN.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);

                for (LoanEmiSchedule emi : schedules) {
                    if ("PAID".equals(emi.getStatus())) continue;

                    BigDecimal interestComponent = remainingPrincipal.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal principalComponent = newEmi.subtract(interestComponent).setScale(2, RoundingMode.HALF_UP);

                    // Last EMI adjustment
                    if (emi == schedules.get(schedules.size() - 1)) {
                        principalComponent = remainingPrincipal;
                        newEmi = principalComponent.add(interestComponent);
                    }

                    emi.setEmiAmount(newEmi);
                    emi.setInterestComponent(interestComponent);
                    emi.setPrincipalComponent(principalComponent);
                    emi.setRemainingPrincipal(remainingPrincipal.subtract(principalComponent));
                    remainingPrincipal = emi.getRemainingPrincipal();

                    loanEmiScheduleRepo.save(emi);
                }
            } else {
                // Loan fully paid, mark all EMIs as PAID
                for (LoanEmiSchedule emi : schedules) {
                    emi.setStatus("PAID");
                    emi.setRemainingPrincipal(BigDecimal.ZERO);
                    emi.setPrincipalComponent(BigDecimal.ZERO);
                    emi.setInterestComponent(BigDecimal.ZERO);
                    loanEmiScheduleRepo.save(emi);
                }
            }

            loanRepo.save(loan);

            // ✅ Send repayment confirmation email
            try {
                mailService.sendEmail(
                    user.getEmail(),
                    "Loan Repayment Confirmation",
                    "Dear " + user.getUsername() + ",\n\n" +
                    "We have successfully received your repayment of " + amount + " for Loan #" + loan.getId() + ".\n" +
                    "Your updated outstanding balance is: " + loan.getRemainingAmount() + ".\n\n" +
                    "Thank you for your payment.\n\n" +
                    "Regards,\nLMS Team"
                );
            } catch (Exception e) {
                e.printStackTrace();
                // not failing the repayment process if email fails
            }

            return ResponseEntity.ok("✅ Payment applied successfully.");
        } else {
            repayment.setStatus("FAILED");
            repaymentRepo.save(repayment);
            return ResponseEntity.badRequest().body("Payment failed: " + bmsResult);
        }
    }



}
