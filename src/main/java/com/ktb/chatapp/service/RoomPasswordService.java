package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Room;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoomPasswordService {

    private final PasswordEncoder passwordEncoder;

    public boolean matches(Room room, String rawPassword) {
        // 비번 없는 방
        if (!room.isHasPassword()) {
            return true;
        }
        if (rawPassword == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, room.getPassword());
    }
}