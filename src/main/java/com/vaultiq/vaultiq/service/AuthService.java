package com.vaultiq.vaultiq.service;

import com.vaultiq.vaultiq.dto.AuthRequest;
import com.vaultiq.vaultiq.dto.AuthResponse;
import com.vaultiq.vaultiq.entity.User;
import com.vaultiq.vaultiq.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JWTService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthResponse register(AuthRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Email already registered")
                    .build();
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .provider(User.AuthProvider.LOCAL)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();

        user = userRepository.save(user);
        String token = jwtService.generateToken(user.getId(), user.getEmail());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .success(true)
                .message("Registration successful")
                .build();
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Invalid email or password")
                    .build();
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        String token = jwtService.generateToken(user.getId(), user.getEmail());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .picture(user.getPicture())
                .success(true)
                .message("Login successful")
                .build();
    }

    public AuthResponse loginOrRegisterOAuthUser(String email, String name,
                                                 String picture, String providerId) {
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            user = User.builder()
                    .email(email)
                    .name(name)
                    .picture(picture)
                    .provider(User.AuthProvider.GOOGLE)
                    .providerId(providerId)
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        } else {
            user.setName(name);
            user.setPicture(picture);
            user.setLastLoginAt(LocalDateTime.now());
        }

        user = userRepository.save(user);
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .picture(user.getPicture())
                .success(true)
                .build();
    }
}