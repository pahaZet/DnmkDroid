package co.tinode.tindroid;

import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.text.TextUtils;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public final class ContactFileHelper {
    private ContactFileHelper() {}

    public static boolean isContactFile(@Nullable String mimeType, @Nullable String fileName) {
        if (!TextUtils.isEmpty(mimeType)) {
            String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("vcard") || normalized.contains("x-vcard") ||
                    "text/directory".equals(normalized) || "application/directory".equals(normalized)) {
                return true;
            }
        }

        return !TextUtils.isEmpty(fileName) &&
                fileName.toLowerCase(Locale.ROOT).endsWith(".vcf");
    }

    @NonNull
    public static String getContactDisplayName(@NonNull Context context,
                                               @Nullable String fileName,
                                               @Nullable byte[] bits) {
        ParsedContact contact = parseContact(bits);
        if (contact != null && !TextUtils.isEmpty(contact.getDisplayName())) {
            return contact.getDisplayName();
        }

        String fallback = stripExtension(fileName);
        if (!TextUtils.isEmpty(fallback)) {
            return fallback;
        }
        return context.getString(R.string.placeholder_contact_title);
    }

    public static void promptAddContact(@NonNull AppCompatActivity activity,
                                        @Nullable String fileName,
                                        @Nullable byte[] bits) {
        showInsertDialog(activity, parseContact(bits), fileName);
    }

    public static void promptAddContact(@NonNull AppCompatActivity activity, @NonNull File file) {
        try {
            showInsertDialog(activity, parseContact(readAll(file)), file.getName());
        } catch (IOException ignored) {
            Toast.makeText(activity, R.string.contact_file_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private static void showInsertDialog(@NonNull AppCompatActivity activity,
                                         @Nullable ParsedContact contact,
                                         @Nullable String fileName) {
        if (contact == null || !contact.hasUsefulData()) {
            Toast.makeText(activity, R.string.contact_file_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        String displayName = contact.getDisplayName();
        if (TextUtils.isEmpty(displayName)) {
            displayName = getContactDisplayName(activity, fileName, null);
        }
        final String finalDisplayName = displayName;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.action_add_contact)
                .setMessage(activity.getString(R.string.confirm_add_contact_from_file, finalDisplayName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> launchInsertContact(activity, contact, finalDisplayName))
                .show();
    }

    private static void launchInsertContact(@NonNull AppCompatActivity activity,
                                            @NonNull ParsedContact contact,
                                            @NonNull String displayName) {
        Intent intent = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
        intent.putExtra(ContactsContract.Intents.Insert.NAME, displayName);

        if (!contact.phones.isEmpty()) {
            ContactValue primaryPhone = contact.phones.get(0);
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, primaryPhone.value);
            intent.putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, toPhoneType(primaryPhone.type));
        }
        if (!contact.emails.isEmpty()) {
            ContactValue primaryEmail = contact.emails.get(0);
            intent.putExtra(ContactsContract.Intents.Insert.EMAIL, primaryEmail.value);
            intent.putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE, toEmailType(primaryEmail.type));
        }
        if (!TextUtils.isEmpty(contact.organization)) {
            intent.putExtra(ContactsContract.Intents.Insert.COMPANY, contact.organization);
        }
        if (!TextUtils.isEmpty(contact.title)) {
            intent.putExtra(ContactsContract.Intents.Insert.JOB_TITLE, contact.title);
        }
        if (!TextUtils.isEmpty(contact.note)) {
            intent.putExtra(ContactsContract.Intents.Insert.NOTES, contact.note);
        }

        ArrayList<ContentValues> rows = new ArrayList<>();

        ContentValues name = new ContentValues();
        name.put(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        name.put(StructuredName.DISPLAY_NAME, displayName);
        if (!TextUtils.isEmpty(contact.givenName)) {
            name.put(StructuredName.GIVEN_NAME, contact.givenName);
        }
        if (!TextUtils.isEmpty(contact.familyName)) {
            name.put(StructuredName.FAMILY_NAME, contact.familyName);
        }
        if (!TextUtils.isEmpty(contact.additionalName)) {
            name.put(StructuredName.MIDDLE_NAME, contact.additionalName);
        }
        if (!TextUtils.isEmpty(contact.prefix)) {
            name.put(StructuredName.PREFIX, contact.prefix);
        }
        if (!TextUtils.isEmpty(contact.suffix)) {
            name.put(StructuredName.SUFFIX, contact.suffix);
        }
        rows.add(name);

        if (!TextUtils.isEmpty(contact.organization) || !TextUtils.isEmpty(contact.title)) {
            ContentValues org = new ContentValues();
            org.put(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE);
            if (!TextUtils.isEmpty(contact.organization)) {
                org.put(Organization.COMPANY, contact.organization);
            }
            if (!TextUtils.isEmpty(contact.title)) {
                org.put(Organization.TITLE, contact.title);
            }
            rows.add(org);
        }

        for (ContactValue phone : contact.phones) {
            ContentValues row = new ContentValues();
            row.put(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
            row.put(Phone.NUMBER, phone.value);
            row.put(Phone.TYPE, toPhoneType(phone.type));
            rows.add(row);
        }

        for (ContactValue email : contact.emails) {
            ContentValues row = new ContentValues();
            row.put(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);
            row.put(Email.ADDRESS, email.value);
            row.put(Email.TYPE, toEmailType(email.type));
            rows.add(row);
        }

        if (!rows.isEmpty()) {
            intent.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows);
        }

        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(activity, R.string.action_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static int toPhoneType(@Nullable String type) {
        if (TextUtils.isEmpty(type)) {
            return Phone.TYPE_OTHER;
        }
        String normalized = type.toUpperCase(Locale.ROOT);
        if (normalized.contains("CELL") || normalized.contains("MOBILE")) {
            return Phone.TYPE_MOBILE;
        }
        if (normalized.contains("WORK") || normalized.contains("BUSINESS")) {
            return Phone.TYPE_WORK;
        }
        if (normalized.contains("HOME") || normalized.contains("PERSONAL")) {
            return Phone.TYPE_HOME;
        }
        return Phone.TYPE_OTHER;
    }

    private static int toEmailType(@Nullable String type) {
        if (TextUtils.isEmpty(type)) {
            return Email.TYPE_OTHER;
        }
        String normalized = type.toUpperCase(Locale.ROOT);
        if (normalized.contains("WORK") || normalized.contains("BUSINESS")) {
            return Email.TYPE_WORK;
        }
        if (normalized.contains("HOME") || normalized.contains("PERSONAL")) {
            return Email.TYPE_HOME;
        }
        return Email.TYPE_OTHER;
    }

    private static @Nullable ParsedContact parseContact(@Nullable byte[] bits) {
        if (bits == null || bits.length == 0) {
            return null;
        }

        String raw = new String(bits, StandardCharsets.UTF_8);
        if (!raw.toUpperCase(Locale.ROOT).contains("BEGIN:VCARD")) {
            return null;
        }

        List<String> lines = unfold(raw);
        ParsedContact contact = new ParsedContact();
        boolean inCard = false;
        for (String line : lines) {
            if (TextUtils.isEmpty(line)) {
                continue;
            }

            String upper = line.toUpperCase(Locale.ROOT);
            if ("BEGIN:VCARD".equals(upper)) {
                inCard = true;
                continue;
            }
            if ("END:VCARD".equals(upper)) {
                break;
            }
            if (!inCard) {
                continue;
            }

            Property property = Property.parse(line);
            if (property == null || TextUtils.isEmpty(property.value)) {
                continue;
            }

            switch (property.name) {
                case "FN":
                    if (TextUtils.isEmpty(contact.fullName)) {
                        contact.fullName = property.value;
                    }
                    break;
                case "N":
                    fillStructuredName(contact, property.value);
                    break;
                case "TEL":
                    contact.addPhone(property.value, property.type);
                    break;
                case "EMAIL":
                    contact.addEmail(property.value, property.type);
                    break;
                case "ORG":
                    if (TextUtils.isEmpty(contact.organization)) {
                        contact.organization = firstComponent(property.value);
                    }
                    break;
                case "TITLE":
                    if (TextUtils.isEmpty(contact.title)) {
                        contact.title = property.value;
                    }
                    break;
                case "NOTE":
                    if (TextUtils.isEmpty(contact.note)) {
                        contact.note = property.value;
                    }
                    break;
                default:
                    break;
            }
        }

        return contact.hasUsefulData() ? contact : null;
    }

    private static void fillStructuredName(@NonNull ParsedContact contact, @NonNull String value) {
        String[] parts = value.split(";", -1);
        if (parts.length > 0 && TextUtils.isEmpty(contact.familyName)) {
            contact.familyName = emptyToNull(parts[0]);
        }
        if (parts.length > 1 && TextUtils.isEmpty(contact.givenName)) {
            contact.givenName = emptyToNull(parts[1]);
        }
        if (parts.length > 2 && TextUtils.isEmpty(contact.additionalName)) {
            contact.additionalName = emptyToNull(parts[2]);
        }
        if (parts.length > 3 && TextUtils.isEmpty(contact.prefix)) {
            contact.prefix = emptyToNull(parts[3]);
        }
        if (parts.length > 4 && TextUtils.isEmpty(contact.suffix)) {
            contact.suffix = emptyToNull(parts[4]);
        }
    }

    @NonNull
    private static List<String> unfold(@NonNull String raw) {
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] physical = normalized.split("\n", -1);
        ArrayList<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quotedPrintable = false;

        for (String line : physical) {
            if (current.length() == 0) {
                current.append(line);
                quotedPrintable = isQuotedPrintableProperty(line);
                continue;
            }

            if (line.startsWith(" ") || line.startsWith("\t")) {
                current.append(line.substring(1));
                quotedPrintable = isQuotedPrintableProperty(current.toString());
                continue;
            }

            if (quotedPrintable && current.charAt(current.length() - 1) == '=') {
                current.setLength(current.length() - 1);
                current.append(line);
                quotedPrintable = isQuotedPrintableProperty(current.toString());
                continue;
            }

            result.add(current.toString());
            current.setLength(0);
            current.append(line);
            quotedPrintable = isQuotedPrintableProperty(line);
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static boolean isQuotedPrintableProperty(@NonNull String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String head = line.substring(0, colon).toUpperCase(Locale.ROOT);
        return head.contains("ENCODING=QUOTED-PRINTABLE");
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private static String stripScheme(@Nullable String value, @NonNull String scheme) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        if (value.regionMatches(true, 0, scheme, 0, scheme.length())) {
            return value.substring(scheme.length());
        }
        return value;
    }

    @NonNull
    private static String firstComponent(@NonNull String value) {
        int separator = value.indexOf(';');
        return separator >= 0 ? value.substring(0, separator) : value;
    }

    @NonNull
    private static String stripExtension(@Nullable String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    @NonNull
    private static byte[] readAll(@NonNull File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class ParsedContact {
        String fullName;
        String givenName;
        String familyName;
        String additionalName;
        String prefix;
        String suffix;
        String organization;
        String title;
        String note;
        final ArrayList<ContactValue> phones = new ArrayList<>();
        final ArrayList<ContactValue> emails = new ArrayList<>();

        @NonNull
        String getDisplayName() {
            if (!TextUtils.isEmpty(fullName)) {
                return fullName;
            }
            ArrayList<String> parts = new ArrayList<>(5);
            if (!TextUtils.isEmpty(prefix)) {
                parts.add(prefix);
            }
            if (!TextUtils.isEmpty(givenName)) {
                parts.add(givenName);
            }
            if (!TextUtils.isEmpty(additionalName)) {
                parts.add(additionalName);
            }
            if (!TextUtils.isEmpty(familyName)) {
                parts.add(familyName);
            }
            if (!TextUtils.isEmpty(suffix)) {
                parts.add(suffix);
            }
            if (!parts.isEmpty()) {
                return TextUtils.join(" ", parts);
            }
            if (!TextUtils.isEmpty(organization)) {
                return organization;
            }
            return "";
        }

        boolean hasUsefulData() {
            return !TextUtils.isEmpty(getDisplayName()) ||
                    !phones.isEmpty() ||
                    !emails.isEmpty() ||
                    !TextUtils.isEmpty(organization) ||
                    !TextUtils.isEmpty(title) ||
                    !TextUtils.isEmpty(note);
        }

        void addPhone(@Nullable String value, @Nullable String type) {
            String normalized = emptyToNull(stripScheme(value, "tel:"));
            if (!TextUtils.isEmpty(normalized)) {
                phones.add(new ContactValue(normalized, type));
            }
        }

        void addEmail(@Nullable String value, @Nullable String type) {
            String normalized = emptyToNull(stripScheme(value, "mailto:"));
            if (!TextUtils.isEmpty(normalized)) {
                emails.add(new ContactValue(normalized, type));
            }
        }
    }

    private static final class ContactValue {
        final String value;
        final String type;

        ContactValue(@NonNull String value, @Nullable String type) {
            this.value = value;
            this.type = type == null ? "" : type;
        }
    }

    private static final class Property {
        final String name;
        final String type;
        final String value;

        private Property(@NonNull String name, @Nullable String type, @NonNull String value) {
            this.name = name;
            this.type = type == null ? "" : type;
            this.value = value;
        }

        @Nullable
        static Property parse(@NonNull String line) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return null;
            }

            String head = line.substring(0, colon);
            String rawValue = line.substring(colon + 1);
            if (TextUtils.isEmpty(rawValue)) {
                return null;
            }

            String[] parts = head.split(";");
            if (parts.length == 0) {
                return null;
            }

            String name = parts[0];
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < name.length()) {
                name = name.substring(dot + 1);
            }
            name = name.toUpperCase(Locale.ROOT);

            String type = "";
            String charsetName = null;
            boolean quotedPrintable = false;
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String key = part.substring(0, eq).toUpperCase(Locale.ROOT);
                    String value = part.substring(eq + 1);
                    if ("TYPE".equals(key)) {
                        type = appendType(type, value);
                    } else if ("CHARSET".equals(key)) {
                        charsetName = value;
                    } else if ("ENCODING".equals(key) && "QUOTED-PRINTABLE".equalsIgnoreCase(value)) {
                        quotedPrintable = true;
                    }
                } else {
                    type = appendType(type, part);
                }
            }

            String value = quotedPrintable ? decodeQuotedPrintable(rawValue, charsetName) : rawValue;
            value = decodeEscapes(value).trim();
            return value.isEmpty() ? null : new Property(name, type, value);
        }

        @NonNull
        private static String appendType(@NonNull String base, @Nullable String addition) {
            if (TextUtils.isEmpty(addition)) {
                return base;
            }
            if (TextUtils.isEmpty(base)) {
                return addition;
            }
            return base + "," + addition;
        }
    }

    @NonNull
    private static String decodeQuotedPrintable(@NonNull String value, @Nullable String charsetName) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '=') {
                if (i + 2 < value.length()) {
                    int high = Character.digit(value.charAt(i + 1), 16);
                    int low = Character.digit(value.charAt(i + 2), 16);
                    if (high >= 0 && low >= 0) {
                        output.write((high << 4) + low);
                        i += 2;
                        continue;
                    }
                }
                continue;
            }
            output.write((byte) ch);
        }

        Charset charset = StandardCharsets.UTF_8;
        if (!TextUtils.isEmpty(charsetName)) {
            try {
                charset = Charset.forName(charsetName);
            } catch (Exception ignored) {
            }
        }
        return new String(output.toByteArray(), charset);
    }

    @NonNull
    private static String decodeEscapes(@NonNull String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'n' || next == 'N') {
                    sb.append('\n');
                } else {
                    sb.append(next);
                }
                i++;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
