package com.LMS.LMS.Controller;

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
import java.util.Map;

@RestController
@RequestMapping("/api/lms")
public class LoanController {

    @Autowired
    private LoanService loanService;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserReppo userReppo;

    // ✅ Utility method for extracting current user from token
    private Users getCurrentUser(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            return null;
        }
        // handle raw or Bearer
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        String username = jwtService.extractUsername(token);
        return userReppo.findByUsername(username);
    }

    /** STEP 2: send account to BMS (USER only) */
    @PostMapping("/account/send")
    public ResponseEntity<?> sendAccountToBms(@RequestBody Map<String, String> payload,
                                              HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_USER".equals(currentUser.getRole())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only users can send accounts");
        }

        String accountNumber = payload.get("accountNumber");
        try {
            return ResponseEntity.ok(loanService.sendAccountToBms(accountNumber, currentUser));
        } catch (Exception e) {
            // log the actual error
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("❌ Failed to send account: " + e.getMessage());
        }
    }

    /** STEP 3: confirm micro-deposit (USER only) */
    @PostMapping("/account/confirm-deposit")
    public ResponseEntity<?> confirmDeposit(@RequestBody Map<String, String> payload,
                                            HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_USER".equals(currentUser.getRole())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only users can confirm deposit");
        }

        return loanService.confirmMicroDeposit(
                payload.get("accountNumber"),
                new BigDecimal(payload.get("microDepositAmount")),
                currentUser
        );
    }


    /** APPLY LOAN (USER only) */
    @PostMapping("/loan/apply")
    public ResponseEntity<?> applyLoan(@RequestBody Map<String, String> payload,
                                       HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_USER".equals(currentUser.getRole())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only users can apply for loans");
        }

        String accountNumber = payload.get("accountNumber");
        BigDecimal amount = new BigDecimal(payload.get("loanAmount"));
        String purpose = payload.get("purpose");
        int termMonths = Integer.parseInt(payload.get("termMonths"));

        return ResponseEntity.ok(loanService.applyLoan(accountNumber, amount, purpose, termMonths, currentUser));
    }

    /** REPAY LOAN (USER only) */
    @PostMapping("/loan/repay")
    public ResponseEntity<?> repayLoan(@RequestBody Map<String, String> payload,
                                       HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_USER".equals(currentUser.getRole())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only users can repay loans");
        }

        String accountNumber = payload.get("accountNumber");
        String amountStr = payload.get("amount");

        if (accountNumber == null || amountStr == null) {
            return ResponseEntity.badRequest().body("❌ accountNumber and amount are required.");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body("❌ Invalid amount format.");
        }

        return loanService.repayLoan(accountNumber, amount, currentUser);
    }



    /** APPROVE LOAN (ADMIN only) */
    @PostMapping("/loan/approve")
    public ResponseEntity<?> approveLoan(@RequestParam int loanApplicationId,
                                         HttpServletRequest request) {
        Users currentUser = getCurrentUser(request);
        if (currentUser == null || !"ROLE_ADMIN".equals(currentUser.getRole())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("❌ Only admin can approve loans");
        }

        return ResponseEntity.ok(loanService.approveLoan(loanApplicationId));
    }
}
