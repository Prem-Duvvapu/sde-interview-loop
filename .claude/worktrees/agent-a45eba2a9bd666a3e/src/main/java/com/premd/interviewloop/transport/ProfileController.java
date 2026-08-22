package com.premd.interviewloop.transport;

import com.premd.interviewloop.profile.CompanyProfile;
import com.premd.interviewloop.profile.ProfileLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileLoader profileLoader;

    public ProfileController(ProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
    }

    @GetMapping
    public Collection<CompanyProfile> listProfiles() {
        return profileLoader.getAllProfiles();
    }

    @GetMapping("/{id}")
    public CompanyProfile getProfile(@PathVariable String id) {
        return profileLoader.getProfile(id);
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadProfiles() {
        profileLoader.reload();
        return ResponseEntity.ok(Map.of(
                "status", "reloaded",
                "count", profileLoader.getAllProfiles().size()
        ));
    }
}
