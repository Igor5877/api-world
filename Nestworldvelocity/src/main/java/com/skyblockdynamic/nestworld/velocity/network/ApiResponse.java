package com.skyblockdynamic.nestworld.velocity.network;

// public class apiResponse {
public record ApiResponse(int statusCode, String body, String errorMessage, boolean isSuccess) {

    public ApiResponse(int statusCode, String body) {
        this(statusCode, body, null, statusCode >= 200 && statusCode < 300);
    }
    public ApiResponse(String errorMessage) {
        this(0, null, errorMessage, false); // 0 for client-side/network errors
    }
}
// }
