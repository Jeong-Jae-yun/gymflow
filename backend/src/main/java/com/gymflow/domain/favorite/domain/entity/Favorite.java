package com.gymflow.domain.favorite.domain.entity;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "favorites",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_favorite_user_resource",
                columnNames = {"user_id", "resource_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Favorite extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_favorite_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "resource_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_favorite_resource")
    )
    private Resource resource;

    @Builder
    public Favorite(User user, Resource resource) {
        validateUser(user);
        validateResource(resource);

        this.user = user;
        this.resource = resource;
    }

    private static void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("Favorite는 User 없이 생성할 수 없습니다.");
        }
    }

    private static void validateResource(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Favorite는 Resource 없이 생성할 수 없습니다.");
        }
    }
}
