package com.gymflow.domain.waitingqueue.controller;

import com.gymflow.domain.waitingqueue.dto.response.PromotionAcceptResponse;
import com.gymflow.domain.waitingqueue.service.PromotionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    @PostMapping("/{promotionId}/accept")
    public ResponseEntity<PromotionAcceptResponse> accept(@PathVariable Long promotionId) {
        PromotionAcceptResponse response = promotionService.accept(promotionId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{promotionId}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long promotionId) {
        promotionService.reject(promotionId);
        return ResponseEntity.noContent().build();
    }
}
