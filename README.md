# PikaReciever
Permits downloading timing data from a [PikaReader](https://github.com/PikaTimer/PikaReader) based system to a local text file. 


# Features
* Connects to one or more local PikaReader systems. 
* Allows for saving both live read data and rewinds from a PikaReader to a local text file.
* Pre-select output format from various pre-defined basic "Chip, Date, Time" CSV file formats or use a custom output format.
* Provides a Chip -> Bib mapping function to allow for reporting a Bib number in place of the raw chip number.
* Constantly appends new data to the output file to enable streaming input into a Timing Application such as RaceDay Scoring
* Displays the status of the PikaReader system (last read, battery level if available, etc) 

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

## Examples

A custom output format of **$B,"$DT",%Z, %{EpochMilli}** would result in the following output:
> 1234,"2026-07-19 11:10:43.524",-06:00,1784481043524

Note that escape codes are case insensitive. You can preface the codes with either a $ or %. Codes can also be enclosed with curly brackets ({}). 
   * Example: The following are all equivalent and will print out the Chip number: $C %C, ${C}, %{CHIP}, ${chip}

For Date and Time fields, you can optionaly specify a date/time formatter by enclosing the code in brackets and by separating the output code by a format string separated by a colon (:).
  * Example: %{D:MM/DD/YYYY} would print 01/02/2026

See the Java API docs for DateTimeFormatter at <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/format/DateTimeFormatter.html#patterns)> for valid data and time formatting codes. 


# Requirements
* Java 25 or newer with JavaFX 25 or newer



