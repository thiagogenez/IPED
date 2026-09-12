import iped.utils.DateUtil;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.TimeZone;

/** Uses java.time's independent ISO parser as the expected-value oracle. */
public class DateTimezoneRepro {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String[] inputs = {
            "2024-01-15T12:00:00+05:30",
            "2024-01-15T12:00:00.123Z",
            "2024-01-15T12:00:00.123+05:00",
            "2024-01-15T12:00:00.123+05:30",
            "2024-01-15T12:00:00.123+05:45",
            "2024-01-15T12:00:00.123-03:30"
        };
        int failures = 0;
        for (String input : inputs) {
            Instant expected = OffsetDateTime.parse(input).toInstant();
            Instant actual = DateUtil.tryToParseDate(input).toInstant();
            boolean ok = expected.equals(actual);
            System.out.printf("%s input=%s%n  expected=%s%n  actual=  %s%n  displacement_seconds=%d%n",
                ok ? "PASS" : "FAIL", input, expected, actual, actual.getEpochSecond() - expected.getEpochSecond());
            if (!ok) failures++;
        }
        if (failures != 0) throw new AssertionError(failures + " timestamp(s) changed meaning");
    }
}
