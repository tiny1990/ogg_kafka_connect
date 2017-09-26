package oracle.goldengate.kafkaconnect.formatter;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;

public class X {

    private static final DateTimeParser[] parsers = {
        DateTimeFormat.forPattern("yyyy-MM-dd:HH:mm:ss.SSSSSS Z").getParser(),
        DateTimeFormat.forPattern("yyyy-MM-dd:HH:mm:ss.SSSSSS").getParser(),// TimeStamp - for some reason the format is a bit different from the GG doc and JDBC standard
        DateTimeFormat.forPattern("yyyy-MM-dd:HH:mm:ss").getParser()
    };
    private static final DateTimeFormatter formatter = new DateTimeFormatterBuilder().append(null, parsers).toFormatter();


    public static void main(String[] args) {


        DateTime time = formatter.parseDateTime("2017-08-17:17:22:19.768000");
        System.out.println(time.getMillis());

    }
}
