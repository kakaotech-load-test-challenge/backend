package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserResponse {

    private String id;
    private String name;
    private String email;
    private String profileImage;

    /**
     * User 엔티티 → Response 변환
     */
    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .profileImage(
                        user.getProfileImage() != null
                                ? user.getProfileImage()
                                : ""
                )
                .build();
    }

    /**
     * sender 스냅샷(Map) → UserResponse 변환
     */
    public static UserResponse fromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return null;

        return UserResponse.builder()
                .id((String) snapshot.getOrDefault("id", null))
                .name((String) snapshot.getOrDefault("name", null))
                .email((String) snapshot.getOrDefault("email", null))
                .profileImage((String) snapshot.getOrDefault("profileImage", ""))
                .build();
    }
}