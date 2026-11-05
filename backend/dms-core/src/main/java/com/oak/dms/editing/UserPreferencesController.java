package com.oak.dms.editing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A user's own interface preferences - self-service like {@code /api/v1/users/me}, so no permission beyond being signed in. */
@RestController
@RequestMapping("/api/v1/users/me/preferences")
@RequiredArgsConstructor
@Tag(name = "User preferences", description = "The signed-in user's own interface preferences")
public class UserPreferencesController {

    public record SaveRequest(String editorLayout) {
    }

    private final UserPreferencesService service;

    @GetMapping
    @Operation(summary = "My preferences (editor layout defaults to fullpage)")
    public UserPreferencesService.View get() {
        return service.mine();
    }

    @PutMapping
    @Operation(summary = "Choose where the online editor opens",
            description = "editorLayout is fullpage (the whole window) or inline (inside the DMS page); 400 EDITOR_LAYOUT_INVALID otherwise.")
    public UserPreferencesService.View save(@RequestBody SaveRequest request) {
        return service.save(request.editorLayout());
    }
}
