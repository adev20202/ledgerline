package io.ledgerline.tenant;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/admin/tenants")
public class AdminTenantController {

    private final TenantService tenantService;

    public AdminTenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    public record CreateTenantRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,63}") String slug) {
    }

    public record TenantView(UUID id, String name, String slug, TenantStatus status, Instant createdAt) {
        static TenantView from(Tenant t) {
            return new TenantView(t.getId(), t.getName(), t.getSlug(), t.getStatus(), t.getCreatedAt());
        }
    }

    public record IssuedKeyView(TenantView tenant, String apiKey) {
        static IssuedKeyView from(TenantService.IssuedKey issued) {
            return new IssuedKeyView(TenantView.from(issued.tenant()), issued.apiKey());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKeyView create(@Valid @RequestBody CreateTenantRequest request) {
        return IssuedKeyView.from(tenantService.create(request.name(), request.slug()));
    }

    @PostMapping("/{id}/rotate-key")
    public IssuedKeyView rotateKey(@PathVariable UUID id) {
        return IssuedKeyView.from(tenantService.rotateKey(id));
    }

    @PostMapping("/{id}/suspend")
    public TenantView suspend(@PathVariable UUID id) {
        return TenantView.from(tenantService.suspend(id));
    }
}
