# API test sequence

1. `GET /actuator/health` → 200
2. `POST /api/v1/admin/api-keys` (admin key, CLIENT) → 201
3. `POST /api/v1/admin/api-keys` (admin key, throwaway) → 201
4. `DELETE /api/v1/admin/api-keys/{keyId}` (throwaway) → 204
5. `GET /api/v1/payments` (revoked key) → 401
6. `POST /api/v1/payments/initiate` → 201
7. `POST /api/v1/payments/initiate` (same body) → 200
8. `POST /api/v1/payments/initiate` (same details, new reference) → 409
9. `POST /api/v1/payments/initiate` (same reference, different amount) → 409
10. `GET /api/v1/payments/verify/{reference}` → 200
11. `GET /api/v1/payments?page=0&size=5&sort=createdAt&direction=desc` → 200
12. `GET /api/v1/payments?reference={reference}&status=PENDING` → 200
13. `GET /api/v1/payments/{transactionId}` → 200
14. `GET /api/v1/payments/{unknownId}` → 404
15. `POST /api/v1/webhooks/paystack` (signed `charge.success`) → 200
16. `POST /api/v1/webhooks/paystack` (same event again) → 200
17. `POST /api/v1/webhooks/paystack` (invalid signature) → 401
18. `POST /api/v1/webhooks/paystack` (unhandled event type) → 200
19. `POST /api/v1/payments/initiate` (payment to review) → 201
20. `POST /api/v1/webhooks/paystack` (signed, wrong amount) → 200
21. `GET /api/v1/payments?status=REQUIRES_REVIEW&reference={reviewReference}` → 200
22. `GET /api/v1/admin/payments/{id}/transitions` → 200
23. `POST /api/v1/admin/payments/{id}/resolution` → 200
24. `POST /api/v1/admin/payments/{id}/resolution` (again) → 409
25. `GET /api/v1/admin/payments/{id}/transitions` → 200
26. `GET /api/v1/payments` (no API key) → 401
27. `POST /api/v1/payments/initiate` (invalid body) → 400
28. `POST /api/v1/admin/api-keys` (client key) → 403
29. `GET /api/v1/payments?size=500` → 400
30. `GET /api/v1/payments?createdFrom=…&createdTo=…` (inverted range) → 400
