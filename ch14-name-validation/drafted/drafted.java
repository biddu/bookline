/**
 * Validates member names during registration.
 */
public final class MemberNameValidator {

    private static final Pattern VALID_NAME =
        Pattern.compile("^[A-Za-z]+([ -][A-Za-z]+)*$");

    private MemberNameValidator() {
    }

    public static boolean isValid(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return VALID_NAME.matcher(name.trim()).matches();
    }
}
