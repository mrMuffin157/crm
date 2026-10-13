# CRM Ticket Window

A customer-service CRM for an e-commerce business selling on several Amazon storefronts. Support
agents use it to open and work tickets (replacements, refunds, manual handling), look up any
sales order by order ID or tracking number, and see a buyer's full order history.

## Features

- **Tickets**: create, edit, close and cancel; comments; per-line-item SKUs and refund amounts;
  full field-level change history; sortable, searchable table with resizable, reorderable columns;
  Excel and CSV export; bulk close from a shipping-label CSV.
- **Sales order lookup**: queries several storefront backends in parallel, then falls back to a
  local archive for older orders. Includes a per-buyer Customer View.
- **Access control**: admin and member roles, plus per-member sales-channel restrictions.
- **Scheduled jobs**: daily failed-ticket and refund-ticket digests, managed from an admin page.
- **Machine-to-machine API**: a token endpoint and JWT-protected ticket endpoints for another
  internal system.

## Tech stack

- **Backend**: Java 25, Spring Boot 4, WebFlux (Netty), Spring Security (reactive), Spring Data
  JPA on MySQL, Hibernate Envers, Quartz (JDBC job store), RabbitMQ, optional Keycloak (OIDC).
- **Frontend**: React 18 and Vite, plain JSX and hand-written CSS.
- **Tests**: JUnit 5 and Mockito.

## Running locally

> **Note:** this project depends on a private library, `exampleco-core` (shared DTOs and enums
> under `com.exampleco.core`), which is not included. It will not build on its own. See
> `settings.gradle.kts`.

1. Create a MySQL database and apply the scripts in `doc/` in version order, plus
   `src/main/resources/schema.sql`.
2. Start RabbitMQ.
3. Copy `.env.example` to `.env` and fill it in, then export it into your shell.
4. Start the backend on `:8080`:

   ```bash
   ./gradlew bootRun
   ```

5. Start the frontend on `:5173` (it proxies `/api` to the backend):

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

6. Sign in as `admin` with the `BOOTSTRAP_ADMIN_PASSWORD` you set.

## Tests

```bash
./gradlew test
```

The unit tests mock their collaborators and need no database. `CrmApplicationTests` boots the
full context and needs a working database profile.
