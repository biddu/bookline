# ch14-name-validation — run 2, verbatim (this is Exhibit 14A in the book)

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Prompt (verbatim): "Add validation for the member name fields on our library signup form. First name and last name. Reject anything that isn't a valid name."
- Edits made: none.
- **Note: cites *Falsehoods Programmers Believe About Names* unprompted in its second sentence. Rejects digits as a stated policy trade-off. Raises mononyms and ships `@NotBlank` on both fields anyway.**

---

Here's how I'd do it — but first, one important caveat that shapes the whole design.

**A word of caution before the code:** "valid name" is a famously dangerous requirement (see *Falsehoods Programmers Believe About Names*). Real members will show up named `O'Brien`, `Anne-Marie`, `van der Berg`, `José`, `Nguyễn`, `Æbelø`, or `李`. If we write a regex like `^[A-Za-z]+$`, we will reject real people and generate support tickets. So the right approach is to **reject what's clearly garbage** (empty, digits, emoji, control characters, absurd length) rather than trying to whitelist "real names." That's what the validator below does — it allows Unicode letters plus the small set of punctuation that legitimately appears in names.

## 1. The constraint annotation

```java
package com.library.signup.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PersonNameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPersonName {

    String message() default "{validation.personName.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int maxLength() default 100;
}
```

## 2. The validator

```java
package com.library.signup.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.text.Normalizer;
import java.util.regex.Pattern;

public class PersonNameValidator implements ConstraintValidator<ValidPersonName, String> {

    /**
     * Allows:
     *  - Unicode letters (\p{L}) and combining marks (\p{M}) — covers accents,
     *    non-Latin scripts (José, Nguyễn, Владимир, 李)
     *  - Apostrophes (' and ’) — O'Brien, D’Angelo
     *  - Hyphens — Anne-Marie, Smith-Jones
     *  - Periods — St. John, Jr.
     *  - Internal spaces — van der Berg, De La Cruz
     *
     * Must start and end with a letter. Separators may not repeat
     * (no "--", "''", "- '" runs).
     */
    private static final Pattern NAME_PATTERN = Pattern.compile(
        "^[\\p{L}\\p{M}]+(?:[\\p{L}\\p{M}]|[ '’\\-.](?=[\\p{L}\\p{M}]))*$"
    );

    private int maxLength;

    @Override
    public void initialize(ValidPersonName annotation) {
        this.maxLength = annotation.maxLength();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Let @NotBlank handle null/empty so each failure gets its own message.
        if (value == null) {
            return true;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return true; // @NotBlank's job
        }

        if (trimmed.length() > maxLength) {
            return fail(context, "{validation.personName.tooLong}");
        }

        // Normalize so decomposed accents (e + ´) match precomposed (é)
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC);

        if (!NAME_PATTERN.matcher(normalized).matches()) {
            return fail(context, "{validation.personName.invalidChars}");
        }

        return true;
    }

    private boolean fail(ConstraintValidatorContext context, String messageTemplate) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(messageTemplate)
               .addConstraintViolation();
        return false;
    }
}
```

## 3. The signup form DTO

```java
package com.library.signup.dto;

import com.library.signup.validation.ValidPersonName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class MemberSignupRequest {

    @NotBlank(message = "{validation.firstName.required}")
    @Size(max = 100, message = "{validation.firstName.tooLong}")
    @ValidPersonName(message = "{validation.firstName.invalid}")
    private String firstName;

    @NotBlank(message = "{validation.lastName.required}")
    @Size(max = 100, message = "{validation.lastName.tooLong}")
    @ValidPersonName(message = "{validation.lastName.invalid}")
    private String lastName;

    // Normalize on the way in so downstream code gets clean values
    public void setFirstName(String firstName) {
        this.firstName = firstName == null ? null : firstName.trim();
    }

    public void setLastName(String lastName) {
        this.lastName = lastName == null ? null : lastName.trim();
    }

    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
}
```

## 4. Controller (Spring Boot)

```java
@PostMapping("/api/members/signup")
public ResponseEntity<MemberResponse> signup(
        @Valid @RequestBody MemberSignupRequest request) {
    // If validation fails, Spring throws MethodArgumentNotValidException
    // before this body runs — handle it in your @ControllerAdvice.
    return ResponseEntity.ok(memberService.register(request));
}
```

## 5. Messages (`ValidationMessages.properties`)

```properties
validation.firstName.required=First name is required
validation.firstName.tooLong=First name must be 100 characters or fewer
validation.firstName.invalid=First name contains characters that aren't allowed
validation.lastName.required=Last name is required
validation.lastName.tooLong=Last name must be 100 characters or fewer
validation.lastName.invalid=Last name contains characters that aren't allowed
```

## 6. Tests — these encode the actual policy, so review them carefully

```java
class PersonNameValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "John", "O'Brien", "D’Angelo", "Anne-Marie", "van der Berg",
        "José", "Nguyễn", "Æbelø", "李", "Müller", "St. John", "Renée"
    })
    void acceptsRealNames(String name) {
        assertThat(validate(name)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "   ",                       // blank
        "John123", "1234",               // digits
        "John_Smith", "a@b.com",         // symbols
        "<script>", "Robert'); DROP",    // injection-looking junk
        "😀", "John 😀",                  // emoji
        "-John", "John-", "'",           // leading/trailing/lone separators
        "Anne--Marie", "O''Brien"        // repeated separators
    })
    void rejectsGarbage(String name) {
        assertThat(validate(name)).isNotEmpty();
    }

    @Test
    void rejectsOverlongNames() {
        assertThat(validate("a".repeat(101))).isNotEmpty();
    }

    private Set<ConstraintViolation<MemberSignupRequest>> validate(String name) {
        MemberSignupRequest req = new MemberSignupRequest();
        req.setFirstName(name);
        req.setLastName("Smith");
        return validator.validate(req);
    }
}
```

## Notes and decisions you may want to push back on

1. **Single-letter names are allowed.** "A" is a real name in some cultures. If product wants a minimum of 2 characters, change the pattern's first `+` accordingly — but I'd advise against it.

2. **Mononyms:** some people genuinely have one name. If that comes up, the fix is making `lastName` optional at the product level, not in this validator.

3. **This is not a security control.** Validation here is about data quality and UX. XSS/SQL injection protection belongs in output encoding and parameterized queries regardless — don't let anyone claim the name regex "handles" that. (It happens to reject `<`, `;`, etc., which is a nice bonus, not a defense.)

4. **Mirror it client-side** for instant feedback, but the server-side constraint is the source of truth — the JS version can be a simplified `^[\p{L}\p{M}][\p{L}\p{M} '’\-.]*$/u` check plus length.

5. **Numbers in names:** legally, names like "X Æ A-12" exist in a few jurisdictions. We're rejecting digits anyway — that's a deliberate trade-off (digits are overwhelmingly typos or junk input). If we get a real complaint, we relax it then.

6. If you're on `javax.validation` rather than `jakarta.validation` (pre-Spring Boot 3), just swap the imports — the code is otherwise identical.

The one-line summary: trim and NFC-normalize, cap the length, require Unicode letters with sparing internal punctuation, and keep the allowed set generous — it's much cheaper to occasionally admit a weird-looking name than to reject a real person's actual name at signup.
