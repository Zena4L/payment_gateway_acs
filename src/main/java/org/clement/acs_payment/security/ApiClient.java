package org.clement.acs_payment.security;

public record ApiClient(String ownerId, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
