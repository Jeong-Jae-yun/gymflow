package com.gymflow.domain.favorite.controller;

import com.gymflow.domain.favorite.dto.response.FavoriteResponse;
import com.gymflow.domain.favorite.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @PostMapping("/{resourceId}")
    public ResponseEntity<FavoriteResponse> addFavorite(@PathVariable Long resourceId) {
        FavoriteResponse response = favoriteService.addFavorite(resourceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{resourceId}")
    public ResponseEntity<Void> removeFavorite(@PathVariable Long resourceId) {
        favoriteService.removeFavorite(resourceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<FavoriteResponse>> getMyFavorites() {
        List<FavoriteResponse> response = favoriteService.getMyFavorites();
        return ResponseEntity.ok(response);
    }
}
