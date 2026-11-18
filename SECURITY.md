# Security Policy

## 1. Project Security Scope

This security policy applies strictly to the source code, database migration assets, backend Spring Boot service, frontend React application, and production container deployment definitions housed within this repository (stockguard).

External services, upstream dependencies, host operating systems, and infrastructure components (such as Docker daemon and PostgreSQL server binaries) are managed under their respective upstream security advisories.

## 2. Supported Versions

STOCKGUARD is actively maintained as an open-source development project. Security considerations and bug fixes are applied to the active branch:

| Version | Status |
| :--- | :--- |
| main (0.0.1-SNAPSHOT) | Supported |
| Prior historical commits | Not Supported |

Historical milestone snapshots, experimental tags, and unmerged development branches do not receive backported security updates.

## 3. Reporting a Vulnerability

If you discover a security vulnerability or potential invariant breach, please report it privately rather than opening a public issue.

### Preferred Reporting Route: GitHub Private Vulnerability Reporting

1. Navigate to the repository's **Security** tab on GitHub:
   https://github.com/arnav-jagetiya/stockguard/security
2. Under **Vulnerability reporting**, click **Report a vulnerability**.
3. Provide the details outlined in the reporting guidelines below.

*Note for repository maintainers:* If private vulnerability reporting is not yet visible, enable it under **Settings > Security > Code security and analysis > Private vulnerability reporting**.

If the GitHub Private Vulnerability Reporting interface is temporarily unavailable, please contact the repository maintainer directly via their GitHub profile (@arnav-jagetiya). Do not submit public GitHub Issues or public pull requests for undisclosed vulnerabilities.

## 4. What to Include in a Security Report

To facilitate effective investigation and triage, please provide:

1. **Vulnerability Summary:** A clear description of the potential vulnerability and affected domain (e.g., authentication, multi-tenant isolation, reservation concurrency, audit integrity).
2. **Affected Components:** Specific files, endpoints, database triggers, or configuration parameters involved.
3. **Reproduction Steps:** Detailed, step-by-step instructions or an isolated proof-of-concept (POC) demonstrating the issue.
4. **Estimated Impact:** Assessment of how the issue could affect confidentiality, integrity, or availability.
5. **Potential Remediation:** Any suggested patch, architectural mitigation, or configuration adjustment, if identified.

## 5. Confidentiality & Sanitization Rules

**CRITICAL REQUIREMENT:** Security reports and proof-of-concept demonstrations must **NEVER** include:
- Real database credentials, passwords, or password hashes.
- Production JWT signing secrets or private keys.
- Real user accounts, session tokens, or cookie values.
- Actual production data or customer records.

All reproduction steps and payloads must use synthetic test fixtures (e.g., the Acme Industrial Supply demo tenant fixtures or local test profiles).

## 6. Disclosure Coordination

- **Review Process:** Reports submitted through GitHub Private Vulnerability Reporting are reviewed in good faith by the project maintainer.
- **Responsible Disclosure:** We ask researchers and reporters to adhere to coordinated vulnerability disclosure principles and refrain from public disclosure until the maintainer has had a reasonable opportunity to investigate, triage, and implement a remediation.
- **Commitments & Capacity:** STOCKGUARD is maintained as an independent open-source project without a dedicated 24/7 security operations center or guaranteed response Service Level Agreements (SLAs). Issues are evaluated and addressed on a best-effort basis according to severity and maintainer availability.
