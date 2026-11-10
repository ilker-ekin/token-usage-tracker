package dev.ekin.tokenusage.analytics;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** The period a rollup row covers. All periods are in UTC. */
public enum Granularity {

	DAY {
		@Override
		public LocalDate periodStart(LocalDate day) {
			return day;
		}

		@Override
		public LocalDate nextPeriodStart(LocalDate periodStart) {
			return periodStart.plusDays(1);
		}
	},

	/** ISO week, starting on Monday. */
	WEEK {
		@Override
		public LocalDate periodStart(LocalDate day) {
			return day.with(DayOfWeek.MONDAY);
		}

		@Override
		public LocalDate nextPeriodStart(LocalDate periodStart) {
			return periodStart.plusWeeks(1);
		}
	},

	MONTH {
		@Override
		public LocalDate periodStart(LocalDate day) {
			return day.withDayOfMonth(1);
		}

		@Override
		public LocalDate nextPeriodStart(LocalDate periodStart) {
			return periodStart.plusMonths(1);
		}
	};

	/** First day of the period that contains {@code day}. */
	public abstract LocalDate periodStart(LocalDate day);

	/** First day of the period after the one starting at {@code periodStart}. */
	public abstract LocalDate nextPeriodStart(LocalDate periodStart);

}
