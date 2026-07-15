-- V8: admin-set processing priority for events in the rating calculation (#335).
--
-- The Pending Calculation view groups matches by event and processes them in order. By default that
-- order is the event's end date (earliest-ending first); an administrator can override it by dragging
-- events, which writes calc_priority here (on the same epoch-day scale as end_date, so a dragged event
-- can be placed between date-ordered neighbours). Null = use end_date (the default).

ALTER TABLE events ADD COLUMN calc_priority DOUBLE PRECISION;
COMMENT ON COLUMN events.calc_priority IS
    'Admin override for calculation processing order (#335); null = order by end_date.';
