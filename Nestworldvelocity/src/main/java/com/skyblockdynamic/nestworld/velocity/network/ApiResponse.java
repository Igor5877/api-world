package com.skyblockdynamic.nestworld.velocity.network;

/**
 * Represents a response from the API.
 *
 * @param statusCode   The HTTP status code.
 * @param body         The response body.
 * @param errorMessage An error message if the request failed.
 * @param isSuccess    Whether the request was successful.
 */
public record ApiResponse(int statusCode, String body, String errorMessage, boolean isSuccess) {

    /**
     * Constructs a new ApiResponse.
     *
     * @param statusCode The HTTP status code.
     * @param body       The response body.
     */
    public ApiResponse(int statusCode, String body) {
        this(statusCode, body, null, statusCode >= 200 && statusCode < 300);
    }
    /**
     * Constructs a new ApiResponse for a failed request.
     *
     * @param errorMessage An error message.
     */
    public ApiResponse(String errorMessage) {
        this(0, null, errorMessage, false); // 0 for client-side/network errors
    }
}
