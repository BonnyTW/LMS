package com.LMS.LMS.Controller;

import com.LMS.LMS.DTO.PendingLoanResponseDto;
import com.LMS.LMS.Model.Users;
import com.LMS.LMS.Reppo.UserReppo;
import com.LMS.LMS.Service.JwtService;
import com.LMS.LMS.Service.LoanService;
import com.LMS.LMS.Service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lms")
public class LoanController {

    @Autowired
    private LoanService loanService;

    

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserReppo userReppo;

    private Users getCurrentUser(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) return null;
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        String username = jwtService.extractUsername(token);
        return userReppo.findByUsername(username);
    }

    @PostMapping("/account/send")
    public ResponseEntity<?> sendAccountToBms(@RequestBody Map<String, String> payload,
                                              HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_USER".equals(currentUser.getRole()))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only users can send accounts");

        return ResponseEntity.ok(loanService.sendAccountToBms(payload.get("accountNumber"), currentUser));
    }

    @PostMapping("/account/confirm-deposit")
    public ResponseEntity<?> confirmDeposit(@RequestBody Map<String, String> payload,
                                            HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_USER".equals(currentUser.getRole()))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only users can confirm deposit");

        return loanService.confirmMicroDeposit(
                payload.get("accountNumber"),
                new BigDecimal(payload.get("microDepositAmount")),
                currentUser
        );
    }

    @PostMapping("/loan/apply")
    public ResponseEntity<?> applyLoan(@RequestBody Map<String, String> payload,
                                       HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_USER".equals(currentUser.getRole()))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only users can apply for loans");

        return ResponseEntity.ok(loanService.applyLoan(
                payload.get("accountNumber"),
                new BigDecimal(payload.get("loanAmount")),
                payload.get("purpose"),
                Integer.parseInt(payload.get("termMonths")),
                currentUser
        ));
    }

    @PostMapping("/loan/repay")
    public ResponseEntity<?> repayLoan(@RequestBody Map<String, String> payload,
                                       HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_USER".equals(currentUser.getRole()))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only users can repay loans");

        return loanService.repayLoan(payload.get("accountNumber"), new BigDecimal(payload.get("amount")), currentUser);
    }

    @PostMapping("/loan/approve")
    public ResponseEntity<?> approveLoan(@RequestParam int loanApplicationId,
                                         HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_ADMIN".equals(currentUser.getRole()))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only admin can approve loans");

        return ResponseEntity.ok(loanService.approveLoan(loanApplicationId));
    }

    /** ADMIN: view pending applications with BMS summary */
    @GetMapping("/loan/pending")
    public ResponseEntity<?> pendingApplications(HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_ADMIN".equals(currentUser.getRole()))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only admin can view pending loans");

        List<PendingLoanResponseDto> pending = loanService.getPendingLoansForAdmin();
        return ResponseEntity.ok(pending);
    }
    
    

}
