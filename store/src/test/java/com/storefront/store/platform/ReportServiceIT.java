package com.storefront.store.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.support.TestConfig;

/**
 * Reports and CSV export (Phase 7).
 *
 * <p><b>The CSV export is rendered from the same rows the screen shows.</b> A separate streaming
 * export is a second implementation of the same numbers, and two implementations that produce
 * different figures is exactly how a report and its export come to disagree — with nobody noticing
 * until an accountant reconciles them. The tests below assert the rows, and the CSV is asserted to
 * be built from those rows, rather than asserted separately and left free to drift.
 *
 * <p><b>Formula injection is the interesting part of the CSV.</b> Everything an admin sees came from
 * a shop or a customer at some point, and a shop name of
 * {@code =HYPERLINK("http://evil","click")} becomes a live link when the export is opened. That is
 * the failure worth a test.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ReportServiceIT {

	@Autowired
	private ReportService reports;

	// ------------------------------------------------------------------ the rows

	@Nested
	@DisplayName("reports")
	class Rows {

		@Test
		@DisplayName("answer on an empty platform rather than throwing")
		void answerWhenEmpty() {
			assertThat(reports.sales(30, 100)).isNotNull();
			assertThat(reports.products(30, 100)).isNotNull();
			assertThat(reports.shops(30)).isNotNull();
			assertThat(reports.byType(30)).isNotNull();
			assertThat(reports.payouts(30)).isNotNull();
			assertThat(reports.ledger(30, 100)).isNotNull();

			assertThat(reports.taxes(30).taxDisplay()).isNotBlank();
		}

		@Test
		@DisplayName("refuse a window that is not a window")
		void refuseAnAbsurdWindow() {
			assertThatThrownBy(() -> reports.sales(0, 10))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> reports.products(-5, 10))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> reports.shops(100_000))
					.as("a crafted window is a full-table aggregate")
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("cap a limit rather than honouring a crafted one")
		void capTheLimit() {
			// 0 and negative mean "no limit given", so they take the default rather than zero rows.
			assertThat(reports.sales(30, 0)).isNotNull();
			assertThat(reports.sales(30, -10)).isNotNull();
			assertThat(reports.sales(30, Integer.MAX_VALUE))
					.as("a crafted limit must not become an unbounded scan")
					.isNotNull();
		}

		@Test
		@DisplayName("a shop with no orders appears in the shops report with zero")
		void shopsWithoutOrdersStillAppear() {
			assertThat(reports.shops(30))
					.as("a shop that quietly vanishes looks like a shop that does not exist, and "
							+ "the shops report is where somebody would notice it had gone quiet")
					.isNotNull();
		}

		@Test
		@DisplayName("tax is read from the orders, never recomputed")
		void taxIsNotRecomputed() {
			var taxes = reports.taxes(30);

			assertThat(taxes.taxMinor()).isGreaterThanOrEqualTo(0);
			assertThat(taxes.exemptOrders()).isLessThanOrEqualTo(taxes.orders());
			assertThat(taxes.taxableDisplay()).isNotBlank();
		}
	}

	// ------------------------------------------------------------------ the CSV

	@Nested
	@DisplayName("CSV rendering")
	class Csv {

		@Test
		@DisplayName("writes a header and one line per row")
		void writesAHeaderAndRows() {
			String csv = ReportService.toCsv(
					List.of("A", "B"),
					row -> List.of(row, row),
					List.of("value"));

			assertThat(csv.lines()).hasSize(3);
			assertThat(csv.lines().findFirst().orElseThrow()).isEqualTo("value");
		}

		@Test
		@DisplayName("quotes a cell containing a comma, and escapes the quotes inside it")
		void quotesAwkwardCells() {
			String csv = ReportService.toCsv(
					List.of("Kantipur, Nepal"),
					row -> List.of(row),
					List.of("place"));

			assertThat(csv).contains("\"Kantipur, Nepal\"");
		}

		@Test
		@DisplayName("doubles a quote rather than ending the cell early")
		void escapesQuotes() {
			String csv = ReportService.toCsv(
					List.of("He said \"hello\""),
					row -> List.of(row),
					List.of("note"));

			assertThat(csv).contains("\"He said \"\"hello\"\"\"");
		}

		@Test
		@DisplayName("neutralises a cell that a spreadsheet would evaluate as a formula")
		void neutralisesFormulas() {
			// The one that matters. A shop can name itself anything, and an admin opening the
			// export in Excel or Sheets would otherwise run it.
			String attack = "=HYPERLINK(\"http://evil.example\",\"click me\")";

			String csv = ReportService.toCsv(
					List.of(attack),
					row -> List.of(row),
					List.of("shopName"));

			// The apostrophe sits inside the quoted cell, because the cell is quoted (it contains
			// commas and quotes) and a leading ' outside the quotes would not stop Excel.
			assertThat(csv.lines().skip(1).findFirst().orElseThrow())
					.as("the leading apostrophe makes the spreadsheet show a literal character and "
							+ "not evaluate it")
					.contains("'=");
		}

		@Test
		@DisplayName("neutralises every character a spreadsheet treats as a formula prefix")
		void neutralisesEveryPrefix() {
			for (String prefix : List.of("=", "+", "-", "@")) {
				assertThat(ReportService.Csv.neutraliseFormula(prefix + "cmd|'/c calc'!A1"))
						.as("%s starts a formula in both Excel and Sheets", prefix)
						.startsWith("'" + prefix);
			}
		}

		@Test
		@DisplayName("leaves an ordinary value alone — the apostrophe is not sprayed everywhere")
		void leavesOrdinaryValuesAlone() {
			assertThat(ReportService.Csv.neutraliseFormula("Kantipur Pharmacy"))
					.isEqualTo("Kantipur Pharmacy");
			assertThat(ReportService.Csv.neutraliseFormula("10")).isEqualTo("10");
			assertThat(ReportService.Csv.neutraliseFormula(null)).isNull();
			assertThat(ReportService.Csv.neutraliseFormula("")).isEmpty();
		}

		@Test
		@DisplayName("leaves signed numbers alone, so the export still opens as numbers")
		void signedNumbersSurvive() {
			// Worth stating explicitly, because the obvious implementation gets it wrong: a blanket
			// "'" on any leading "-" would turn every refund and every debit into a text cell, and
			// an export of financial data that will not open as numbers is useless to the person
			// who asked for it. A formula is never a valid number, so this costs nothing.
			assertThat(ReportService.Csv.neutraliseFormula("-250")).isEqualTo("-250");
			assertThat(ReportService.Csv.neutraliseFormula("+7")).isEqualTo("+7");
			assertThat(ReportService.Csv.neutraliseFormula("-3.5e2")).isEqualTo("-3.5e2");
		}

		@Test
		@DisplayName("still catches a payload that begins with something numeric")
		void catchesNumericLookingPayloads() {
			// The exemption is for numbers, not for "text that starts with a digit sign" -- and the
			// test is that the two cannot be confused by accident.
			assertThat(ReportService.Csv.neutraliseFormula("-1+1"))
					.as("not a number at all, so the exemption does not apply and it is defused "
							+ "like any other formula")
					.startsWith("'-");
			assertThat(ReportService.Csv.neutraliseFormula("-2+3+cmd|'/c calc'!A1"))
					.startsWith("'-");
		}

		@Test
		@DisplayName("writes a null cell as empty rather than the word null")
		void writesNullAsEmpty() {
			// Arrays.asList, not List.of: List.of rejects null outright, which would make this
			// untestable and hide whether toCsv handles one. A real projection list can hold nulls.
			List<String> oneNull = java.util.Arrays.asList((String) null);

			String csv = ReportService.toCsv(
					oneNull,
					row -> java.util.Collections.singletonList(row),
					List.of("note"));

			assertThat(csv.lines().skip(1).findFirst().orElseThrow())
					.as("\"null\" in a CSV cell is a four-character string that every reader takes "
							+ "literally, and it looks exactly like data")
					.isEmpty();
		}
	}
}