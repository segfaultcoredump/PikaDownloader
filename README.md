# PikaDownloader
Permits downloading timing data from a [PikaReader](https://github.com/PikaTimer/PikaReader) based system to a local text file. 


# Features
* Connects to one or more local PikaReader systems. 
* Allows for saving both live read data and rewinds from a PikaReader to a local text file.
* Pre-select output format from various pre-defined basic "Chip, Date, Time" CSV file formats or use a custom output format.
* Provides a Chip -> Bib mapping function to allow for reporting a Bib number in place of the raw chip number. Leading zeroes on chip numbers are ignored when matching. If the output includes a Bib but no Chip -> Bib map is in use, the chip number is written in its place and the Output Settings pane shows a warning.
* Optionally only outputs reads for chips that are in the Chip -> Bib map. Trigger reads (chip `0`) are always output.
* Optionally overrides the timezone offset of the reads written to the output files. Turn on "Override Timezone Offset" and pick a fixed UTC offset from -12:00 to +14:00 in 15-minute steps (the default is the computer's current offset). The date, time and timezone of each read are recomputed from its EpochMilli, which does not change. Milliseconds are kept. The Review Reads window also shows times at the override offset. The override is not saved and is off each time PikaDownloader starts.
* Appends new data in realtime to the output file to enable continuous importing into a timing application.
* Displays the status of the PikaReader system (last read, battery level if available, etc).

# Custom Output Formatting
By selecting "Custom" as the output format, you can specify exactly how you want the reads to be written to a file.

The system accepts a string with embeded escape codes that represent various fields you want to have displayed.

## Escape Codes
| Short Code | Long Code      | Description                 | Example Output          |
|------------|----------------|-----------------------------|-------------------------|
| B          | BIB            | Bib number                  | 1234                    |
| C          | CHIP           | Chip Number                 | 1234                    |
| DT         | DateTime       | Date and Time               | 2026-01-02 01:02:03.456 |
| D          | Date           | Date                        | 2026-01-02              |
| T          | Time           | Time w/ milliseconds        | 01:02:03.456            |
| Z or TZ    | TimeZone       | Timezone                    | -06:00                  |
| E          | EpochMilli     | Milliseconds since the EPOC | 1784481043524           |
| A          | Antenna        | Antenna Number              | 1                       |
| R          | Reader         | Reader Number               | 2                       |
| S          | RSSI           | RSSI in dBm                 | -41.2                   |

## Examples

A custom output format of **`$C,"$DT",%Z,%{EpochMilli}`** would result in the following output:
> `1234,"2026-07-19 11:10:43.524",-06:00,1784481043524`

Note that escape codes are case insensitive. You can preface the codes with either a $ or %. Codes can also be enclosed with curly brackets ({}). 
   * Example: The following are all equivalent and will print out the Chip number: `$C, %C, ${C}, %{CHIP}, ${chip}`

For Date and Time fields, you can optionaly specify a date/time formatter by enclosing the code in brackets and by separating the output code by a format string separated by a colon (:).
  * Example: `%{D:MM/DD/YYYY}` would print `01/02/2026`

While the timezone override is on, the Date, Time, DateTime and TimeZone codes (including formatted ones like `%{D:MM/DD/YYYY}`) use the override offset. EpochMilli is never changed.

See the Java API docs for DateTimeFormatter at <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/format/DateTimeFormatter.html#patterns)> for valid data and time formatting codes. 


# Requirements
* Java 25 or newer with JavaFX 25 or newer. Installable binaries are available at <https://github.com/PikaTimer/PikaDownloader/releases>



