# ch14-name-validation — run 1, verbatim

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Prompt: identical to run 2.
- Edits made: none.
- **Note: names `^[A-Za-z]+$` as "the classic mistake" in its second sentence, and gives the best line in either run on the curly apostrophe. Returns `false` on null after writing out why `true` is better.**

---

Here's how I'd do it, along with an important caveat about what "a valid name" means.

**The caveat first:** be careful how strict you make this. Real names include things like `O'Brien`, `Anne-Marie`, `van der Berg`, `José`, `Nguyễn`, `李`, and single-letter names. The classic mistake is `^[A-Za-z]+$`, which rejects a big chunk of your actual members. So my recommended rule is: *reject what's definitely garbage* (digits, symbols, empty/whitespace-only, absurd lengths) rather than trying to enumerate what's allowed in ASCII. Concretely: Unicode letters plus a small set of joiners (space, hyphen, apostrophe, period), must contain at least one letter, sane length bounds.

**Implementation** — assuming Spring Boot / Jakarta Bean Validation, a custom constraint keeps the form DTO clean and reusable for both fields:

```java
// ValidPersonName.java
package com.library.signup.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PersonNameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPersonName {
    String message() default "must be a valid name";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

```java
// PersonNameValidator.java
package com.library.signup.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.text.Normalizer;
import java.util.regex.Pattern;

public class PersonNameValidator implements ConstraintValidator<ValidPersonName, String> {

    private static final int MAX_LENGTH = 100;

    // Unicode letters and marks (covers accents/diacritics), plus
    // space, hyphen, apostrophe (straight and curly), and period.
    private static final Pattern ALLOWED =
        Pattern.compile("^[\\p{L}\\p{M}' \\-.\u2019]+$");

    // Must contain at least one actual letter (rejects "---", "'.", etc.)
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");

    // No doubled punctuation like "--" or "''", and can't start/end with punctuation
    private static final Pattern BAD_EDGES =
        Pattern.compile("^[' \\-.\u2019]|[' \\-\u2019]$|[\\-'.\u2019]{2,}");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null) {
            return false; // pair with @NotNull semantics; see note below
        }
        String name = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);

        return !name.isEmpty()
            && name.length() <= MAX_LENGTH
            && ALLOWED.matcher(name).matches()
            && HAS_LETTER.matcher(name).find()
            && !BAD_EDGES.matcher(name).find();
    }
}
```

Usage on the signup form DTO:

```java
public class MemberSignupForm {

    @NotBlank(message = "First name is required")
    @ValidPersonName(message = "First name contains invalid characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @ValidPersonName(message = "Last name contains invalid characters")
    private String lastName;

    // getters/setters
}
```

And make sure the controller actually triggers validation:

```java
@PostMapping("/signup")
public String signup(@Valid @ModelAttribute MemberSignupForm form,
                     BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
        return "signup"; // re-render with field errors
    }
    memberService.register(form);
    return "redirect:/signup/success";
}
```

**Notes on the decisions in there:**

- **What gets rejected:** empty/blank, anything with digits or symbols (`@`, `#`, `<`, `;` etc.), names over 100 chars, and junk like `"---"` or leading/trailing/doubled punctuation. That catches typos, field-swap mistakes (email pasted into the name box), and script-injection-looking input.
- **What gets accepted:** `O'Brien`, `Anne-Marie`, `José`, `Björk`, `van der Berg`, `Nguyễn`, CJK names. `\p{L}` and `\p{M}` handle all Unicode letters and combining accents, and NFC normalization means `é` validates the same whether it's one codepoint or `e` + combining accent.
- **Curly apostrophe (`\u2019`)** is included because mobile keyboards autocorrect `'` to `’` — without it you'll reject half the iPhone users named O'Connor.
- **Null handling:** conventionally a custom validator returns `true` for null and you let `@NotBlank` own the "required" check — that gives users a "required" message rather than "invalid" for an empty field. I've returned `false` here for belt-and-braces, but if you want the nicer messages, change it to `return true;` on null and rely on `@NotBlank` (which is on the DTO anyway).
- **Length trim:** I validate the trimmed value but don't mutate the field — do the actual trimming in your service/mapper before persisting, or add a `StringTrimmerEditor`/Jackson deserializer if you want it globally.
- **This is not security sanitization.** Validation reduces garbage data, but keep parameterized queries and output-escaping regardless — never rely on a name regex to prevent SQLi/XSS.

If you're on `javax.validation` (pre-Spring Boot 3) instead of `jakarta.validation`, it's the same code with the old package names.

One thing I'd flag for the team: if the library serves a community where mononyms are common (single legal name), consider making *last* name optional and only requiring one of the two — that's a product decision, not a code one, but worth raising before this ships.
