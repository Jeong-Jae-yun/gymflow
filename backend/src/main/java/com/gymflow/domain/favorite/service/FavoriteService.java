package com.gymflow.domain.favorite.service;

import com.gymflow.domain.favorite.domain.entity.Favorite;
import com.gymflow.domain.favorite.domain.repository.FavoriteRepository;
import com.gymflow.domain.favorite.dto.response.FavoriteResponse;
import com.gymflow.domain.favorite.mapper.FavoriteMapper;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;

    @Transactional
    public FavoriteResponse addFavorite(Long resourceId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (resource.getStatus() != ResourceStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_ACTIVE);
        }

        if (favoriteRepository.existsByUserIdAndResourceId(currentUserId, resourceId)) {
            throw new BusinessException(ErrorCode.FAVORITE_ALREADY_EXISTS);
        }

        User user = userRepository.getReferenceById(currentUserId);

        Favorite favorite = Favorite.builder()
                .user(user)
                .resource(resource)
                .build();

        Favorite savedFavorite = favoriteRepository.save(favorite);

        return FavoriteMapper.toResponse(savedFavorite);
    }

    @Transactional
    public void removeFavorite(Long resourceId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Favorite favorite = favoriteRepository.findByUserIdAndResourceId(currentUserId, resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAVORITE_NOT_FOUND));

        favoriteRepository.delete(favorite);
    }

    public List<FavoriteResponse> getMyFavorites() {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        return favoriteRepository.findAllByUserIdOrderByCreatedAtDesc(currentUserId).stream()
                .map(FavoriteMapper::toResponse)
                .toList();
    }
}
