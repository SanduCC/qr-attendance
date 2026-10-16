package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.jayway.jsonpath.JsonPath;
import io.github.sanducc.attendance.checkin.AttendanceRecordService.InvalidNameException;
import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.session.SessionService.SessionSummary;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** #5 (teachers correct student names) and #10 (manual entries), with the anti-cheat rules for real scans intact. */
@ExtendWith(OutputCaptureExtension.class)
class TeacherRecordEditsTest extends IntegrationTestSupport {

    private static final String PROF_B = "prof.b@university.example.ro";

    @Autowired AttendanceRecordService recordService;

    // ------------------------------------------------------------------ #5: edit names

    @Test
    void teacherCorrectsAStudentsNameEverywhere(CapturedOutput output) throws Exception {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popesu");
        AttendanceRecord today = insertRecord(f.sessionId(), ana);
        UUID lastWeek = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        insertRecord(lastWeek, ana);

        rename(f, today.getId(), "  Ana  Maria ", " Popescu ").andExpect(status().isNoContent());

        Student renamed = studentRepository.findById(ana.getId()).orElseThrow();
        assertThat(renamed.getFirstName()).as("trimmed and collapsed like the confirm form").isEqualTo("Ana Maria");
        assertThat(renamed.getLastName()).isEqualTo("Popescu");
        assertThat(renamed.getProviderSubject()).isEqualTo("sub-ana");
        // Global: the other session shows the corrected name as well.
        mvc.perform(get("/sessions/{id}/records", lastWeek).with(owner(f)))
                .andExpect(jsonPath("$.records[0].firstName").value("Ana Maria"))
                .andExpect(jsonPath("$.records[0].lastName").value("Popescu"))
                .andExpect(jsonPath("$.records[0].manual").value(false))
                .andExpect(jsonPath("$.records[0].registered").value(true));
        String csv = mvc.perform(get("/sessions/{id}/export.csv", f.sessionId()).with(owner(f)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains(";Popescu;Ana Maria;");
        // Audit log: ids only, never names or emails.
        assertThat(output).contains("renamed student " + ana.getId() + " via record " + today.getId());
        assertThat(output).doesNotContain("Popescu", "sub-ana@student.example.ro");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''          | Popescu    | firstName",
            "Ana         | '   '      | lastName",
            "Ana3        | Popescu    | firstName",
            "Ana         | =SUM(A1)   | lastName",
            "<script>    | Popescu    | firstName",
    })
    void invalidNamesAreRejectedWithTheConfirmFormRules(String first, String last, String field) throws Exception {
        Fixture f = openFixture();
        AttendanceRecord record = insertRecord(f.sessionId(), insertStudent("sub-ana", "Ana", "Popescu"));

        rename(f, record.getId(), first, last)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty());
        mvc.perform(post("/sessions/{id}/records", f.sessionId()).with(csrf()).with(owner(f))
                        .param("firstName", first).param("lastName", last))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty());

        Student unchanged = findStudent("sub-ana").orElseThrow();
        assertThat(unchanged.getFirstName()).isEqualTo("Ana");
        assertThat(unchanged.getLastName()).isEqualTo("Popescu");
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    @Test
    void namesLongerThan100CharactersAreRejected() {
        Fixture f = openFixture();
        AttendanceRecord record = insertRecord(f.sessionId(), insertStudent("sub-ana", "Ana", "Popescu"));

        assertThatThrownBy(() -> recordService.renameEntry(f.teacherId(), f.sessionId(), record.getId(),
                new NameForm("A".repeat(101), "Popescu")))
                .isInstanceOfSatisfying(InvalidNameException.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("firstName"));
        assertThatThrownBy(() -> recordService.addManualEntry(f.teacherId(), f.sessionId(),
                new NameForm("Ana", null)))
                .isInstanceOfSatisfying(InvalidNameException.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("lastName"));

        recordService.renameEntry(f.teacherId(), f.sessionId(), record.getId(),
                new NameForm("A".repeat(100), "Ștefănescu-Țăranu"));
        assertThat(findStudent("sub-ana").orElseThrow().getLastName()).isEqualTo("Ștefănescu-Țăranu");
    }

    @Test
    void renamingRequiresCsrf() throws Exception {
        Fixture f = openFixture();
        AttendanceRecord record = insertRecord(f.sessionId(), insertStudent("sub-ana", "Ana", "Popescu"));

        mvc.perform(post("/sessions/{id}/records/{rid}/name", f.sessionId(), record.getId()).with(owner(f))
                        .param("firstName", "X").param("lastName", "Y"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/sessions/{id}/records", f.sessionId()).with(owner(f))
                        .param("firstName", "Carmen").param("lastName", "Dobre"))
                .andExpect(status().isForbidden());

        assertThat(findStudent("sub-ana").orElseThrow().getFirstName()).isEqualTo("Ana");
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    @Test
    void studentsStillCannotChangeTheirOwnNameAfterATeachersCorrection() throws Exception {
        Fixture f = openFixture();
        RequestPostProcessor ana = studentLogin("sub-ana", "Ana", "Popesu");
        Browser phone = newBrowser();
        checkIn(phone, f.sessionId(), ana, "Ana", "Popesu").andExpect(redirectedUrl(doneUrl(f.sessionId())));
        long recordId = recordRepository.findAll().getFirst().getId();

        rename(f, recordId, "Ana", "Popescu").andExpect(status().isNoContent());

        clock.advance(Duration.ofDays(7));
        UUID nextWeek = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        Browser samePhone = phone.sameDeviceNewSession();
        scan(samePhone, nextWeek, validToken(nextWeek)).andExpect(status().isOk());
        confirm(samePhone, nextWeek, ana)
                .andExpect(status().isOk())
                .andExpect(model().attribute("registered", true));
        postCheckIn(samePhone, nextWeek, ana, "Anastasia", "Popesu")
                .andExpect(redirectedUrl(doneUrl(nextWeek)));

        Student student = findStudent("sub-ana").orElseThrow();
        assertThat(student.getFirstName()).isEqualTo("Ana");
        assertThat(student.getLastName()).as("the teacher's correction sticks").isEqualTo("Popescu");
        done(samePhone, nextWeek, ana).andExpect(model().attribute("lastName", "Popescu"));
    }

    @Test
    void renamingAManualEntryWithoutAnAccountChangesThatEntryOnly() throws Exception {
        Fixture f = openFixture();
        long carmen = addByName(f, "Carmen", "Dobe");
        long other = addByName(f, "Ion", "Dobre");

        rename(f, carmen, "Carmen", "Dobre").andExpect(status().isNoContent());
        AttendanceRecord record = recordRepository.findById(carmen).orElseThrow();
        assertThat(record.getManualFirstName()).isEqualTo("Carmen");
        assertThat(record.getManualLastName()).isEqualTo("Dobre");
        assertThat(studentRepository.count()).isZero();

        // Two entries without an account can't end up with the same name.
        rename(f, other, "carmen", "DOBRE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Someone with this name is already in this session"));
        assertThat(recordRepository.findById(other).orElseThrow().getManualFirstName()).isEqualTo("Ion");
    }

    // ------------------------------------------------------------------ #10: manual entries

    @Test
    void teacherAddsACourseStudentByHand(CapturedOutput output) throws Exception {
        Fixture f = openFixture(1);
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        Student bogdan = insertStudent("sub-bob", "Bogdan", "Ionescu");
        UUID lastWeek = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        insertRecord(lastWeek, ana);
        insertRecord(lastWeek, bogdan);
        insertRecord(f.sessionId(), ana);
        clock.advance(Duration.ofMinutes(2));

        // The picker offers the course's students who aren't in this session yet.
        mvc.perform(get("/sessions/{id}/students", f.sessionId()).with(owner(f)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(bogdan.getId()))
                .andExpect(jsonPath("$[0].lastName").value("Ionescu"));

        MvcResult added = addStudent(f, bogdan.getId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkedInAt").value("09:02:05"))
                .andReturn();
        long recordId = ((Number) JsonPath.read(added.getResponse().getContentAsString(), "$.recordId")).longValue();

        AttendanceRecord record = recordRepository.findById(recordId).orElseThrow();
        assertThat(record.isManual()).isTrue();
        assertThat(record.getStudentId()).isEqualTo(bogdan.getId());
        assertThat(record.getAddedByTeacherId()).isEqualTo(f.teacherId());
        assertThat(record.getDeviceIdHash()).as("no device: not scanned").isNull();
        assertThat(record.getIpHash()).isNull();
        assertThat(record.getUserAgent()).isNull();
        assertThat(record.getManualFirstName()).isNull();

        // Live table, counter and headcount check include it.
        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(owner(f)))
                .andExpect(jsonPath("$.checkedIn").value(2))
                .andExpect(jsonPath("$.expectedHeadcount").value(1))
                .andExpect(jsonPath("$.overHeadcount").value(true))
                .andExpect(jsonPath("$.records[1].firstName").value("Bogdan"))
                .andExpect(jsonPath("$.records[1].email").value("sub-bob@student.example.ro"))
                .andExpect(jsonPath("$.records[1].manual").value(true))
                .andExpect(jsonPath("$.records[1].registered").value(true))
                .andExpect(jsonPath("$.records[0].manual").value(false));
        mvc.perform(get("/sessions/{id}/students", f.sessionId()).with(owner(f)))
                .andExpect(jsonPath("$", hasSize(0)));
        assertThat(output).contains("Teacher " + f.teacherId() + " added manual record " + recordId
                + " for student " + bogdan.getId() + " to session " + f.sessionId());
        assertThat(output).doesNotContain("Ionescu");
    }

    @Test
    void teacherAddsSomeoneWithoutAnAccount() throws Exception {
        Fixture f = openFixture();

        long recordId = addByName(f, "  Carmen ", "Dobre  ");

        AttendanceRecord record = recordRepository.findById(recordId).orElseThrow();
        assertThat(record.isManual()).isTrue();
        assertThat(record.getStudentId()).isNull();
        assertThat(record.getManualFirstName()).isEqualTo("Carmen");
        assertThat(record.getManualLastName()).isEqualTo("Dobre");
        assertThat(record.getAddedByTeacherId()).isEqualTo(f.teacherId());
        assertThat(record.getDeviceIdHash()).isNull();
        assertThat(studentRepository.count()).as("no student row for a free-text entry").isZero();

        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(owner(f)))
                .andExpect(jsonPath("$.checkedIn").value(1))
                .andExpect(jsonPath("$.records[0].firstName").value("Carmen"))
                .andExpect(jsonPath("$.records[0].lastName").value("Dobre"))
                .andExpect(jsonPath("$.records[0].email").value(nullValue()))
                .andExpect(jsonPath("$.records[0].manual").value(true))
                .andExpect(jsonPath("$.records[0].registered").value(false));
    }

    @Test
    void duplicateManualEntryIsAlreadyCheckedIn() throws Exception {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        UUID lastWeek = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        insertRecord(lastWeek, ana);
        Student bogdan = insertStudent("sub-bob", "Bogdan", "Ionescu");
        insertRecord(lastWeek, bogdan);
        insertRecord(f.sessionId(), bogdan); // scanned in for real
        addStudent(f, ana.getId()).andExpect(status().isCreated());
        addByName(f, "Carmen", "Dobre");
        clock.advance(Duration.ofMinutes(5));

        addStudent(f, ana.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already checked in at 09:00"));
        addStudent(f, bogdan.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already checked in at 09:00"));
        mvc.perform(post("/sessions/{id}/records", f.sessionId()).with(csrf()).with(owner(f))
                        .param("firstName", "carmen").param("lastName", " DOBRE "))
                .andExpect(status().isConflict());

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(3);
    }

    @Test
    void aStudentAddedByHandWhoScansLaterIsAlreadyCheckedIn() throws Exception {
        Fixture f = openFixture();
        RequestPostProcessor ana = studentLogin("sub-ana", "Ana", "Popescu");
        UUID lastWeek = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        checkIn(newBrowser(), lastWeek, ana, "Ana", "Popescu").andExpect(redirectedUrl(doneUrl(lastWeek)));
        Student student = findStudent("sub-ana").orElseThrow();
        addStudent(f, student.getId()).andExpect(status().isCreated());
        clock.advance(Duration.ofMinutes(3));

        Browser phone = newBrowser();
        checkIn(phone, f.sessionId(), ana, "Ana", "Popescu").andExpect(redirectedUrl(doneUrl(f.sessionId())));
        done(phone, f.sessionId(), ana)
                .andExpect(view().name("student/done"))
                .andExpect(model().attribute("justCheckedIn", false))
                .andExpect(model().attribute("checkedInAt", "09:00"));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
        assertThat(recordRepository.findBySessionIdAndStudentId(f.sessionId(), student.getId()).orElseThrow()
                .isManual()).isTrue();
    }

    @Test
    void manualEntriesDontBlockOrAlterALaterRealCheckIn() throws Exception {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        UUID lastWeek = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        insertRecord(lastWeek, ana);
        long manualAna = ((Number) JsonPath.read(addStudent(f, ana.getId()).andReturn().getResponse()
                .getContentAsString(), "$.recordId")).longValue();
        long manualCarmen = addByName(f, "Carmen", "Dobre");
        long manualIon = addByName(f, "Ion", "Dobre");

        // Three entries with a NULL device hash coexist, and Bogdan still checks in from his own phone.
        Browser bogdansPhone = newBrowser();
        checkIn(bogdansPhone, f.sessionId(), studentLogin("sub-bob", "Bogdan", "Ionescu"), "Bogdan", "Ionescu")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));

        Student bogdan = findStudent("sub-bob").orElseThrow();
        AttendanceRecord real = recordRepository.findBySessionIdAndStudentId(f.sessionId(), bogdan.getId())
                .orElseThrow();
        assertThat(real.isManual()).isFalse();
        assertThat(real.getAddedByTeacherId()).isNull();
        assertThat(real.getDeviceIdHash()).isEqualTo(hasher.hash("device", bogdansPhone.rawDeviceId()));
        assertThat(real.getIpHash()).isEqualTo(hasher.hash("ip", RAW_TEST_IP));
        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(4);
        for (long id : List.of(manualAna, manualCarmen, manualIon)) {
            AttendanceRecord manual = recordRepository.findById(id).orElseThrow();
            assertThat(manual.isManual()).isTrue();
            assertThat(manual.getDeviceIdHash()).isNull();
        }

        // Rule 4 still holds for real scans: a friend on Bogdan's phone is rejected.
        Browser borrowed = bogdansPhone.sameDeviceNewSession();
        scan(borrowed, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        postCheckIn(borrowed, f.sessionId(), studentLogin("sub-dan", "Dan", "Pop"), "Dan", "Pop")
                .andExpect(status().isConflict());
        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(4);
    }

    @Test
    void manualEntriesWorkAfterTheSessionClosedAndCanBeRemoved() throws Exception {
        Fixture f = openFixture();
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(post("/sessions/{id}/close", f.sessionId()).with(csrf()).with(owner(f)))
                .andExpect(status().is3xxRedirection());
        clock.advance(Duration.ofHours(2));

        long recordId = addByName(f, "Carmen", "Dobre");
        rename(f, recordId, "Carmen", "Dobre-Pop").andExpect(status().isNoContent());
        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(owner(f)))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.checkedIn").value(1))
                .andExpect(jsonPath("$.records[0].lastName").value("Dobre-Pop"));
        // The course page's history count includes it.
        List<SessionSummary> history = sessionService.listSessionSummaries(f.teacherId(), f.courseId());
        assertThat(history).extracting(SessionSummary::checkedIn).containsExactly(1L);

        mvc.perform(delete("/sessions/{id}/records/{rid}", f.sessionId(), recordId).with(csrf()).with(owner(f)))
                .andExpect(status().isNoContent());
        assertThat(recordRepository.existsById(recordId)).isFalse();
    }

    @Test
    void onlyTheCoursesOwnStudentsCanBeAddedByHand() throws Exception {
        Fixture mine = openFixture();
        Fixture theirs = openFixture();
        Student stranger = insertStudent("sub-x", "Xenia", "Stan");
        insertRecord(theirs.sessionId(), stranger);
        Student neverSeen = insertStudent("sub-y", "Yvonne", "Stan");

        addStudent(mine, stranger.getId()).andExpect(status().isNotFound());
        addStudent(mine, neverSeen.getId()).andExpect(status().isNotFound());
        addStudent(mine, 999_999L).andExpect(status().isNotFound());

        assertThat(recordRepository.countBySessionId(mine.sessionId())).isZero();
    }

    // ------------------------------------------------------------------ ownership + deactivation

    @Test
    void anotherTeacherGets404ForNamesManualEntriesAndRemoval() throws Exception {
        Fixture mine = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        AttendanceRecord real = insertRecord(mine.sessionId(), ana);
        long manual = addByName(mine, "Carmen", "Dobre");
        Teacher other = createTeacher(PROF_B);
        RequestPostProcessor profB = teacherLogin(PROF_B);
        UUID s = mine.sessionId();

        mvc.perform(post("/sessions/{id}/records/{rid}/name", s, real.getId()).with(csrf()).with(profB)
                .param("firstName", "Hacked").param("lastName", "Name")).andExpect(status().isNotFound());
        mvc.perform(post("/sessions/{id}/records/{rid}/name", s, manual).with(csrf()).with(profB)
                .param("firstName", "Hacked").param("lastName", "Name")).andExpect(status().isNotFound());
        mvc.perform(post("/sessions/{id}/records", s).with(csrf()).with(profB)
                .param("firstName", "Hacked").param("lastName", "Name")).andExpect(status().isNotFound());
        mvc.perform(post("/sessions/{id}/records", s).with(csrf()).with(profB)
                .param("studentId", String.valueOf(ana.getId()))).andExpect(status().isNotFound());
        mvc.perform(get("/sessions/{id}/students", s).with(profB)).andExpect(status().isNotFound());
        mvc.perform(delete("/sessions/{id}/records/{rid}", s, manual).with(csrf()).with(profB))
                .andExpect(status().isNotFound());

        // Service layer, not only the controller.
        long b = other.getId();
        assertThatThrownBy(() -> recordService.renameEntry(b, s, real.getId(), new NameForm("Hacked", "Name")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> recordService.addManualEntry(b, s, new NameForm("Hacked", "Name")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> recordService.addManualEntry(b, s, ana.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> recordService.listCourseStudents(b, s)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> recordService.removeRecord(b, s, manual)).isInstanceOf(NotFoundException.class);
        // A record id of my session can't be renamed through prof B's own session either.
        Fixture hers = fixtureFor(other);
        assertThatThrownBy(() -> recordService.renameEntry(b, hers.sessionId(), real.getId(),
                new NameForm("Hacked", "Name"))).isInstanceOf(NotFoundException.class);

        assertThat(findStudent("sub-ana").orElseThrow().getFirstName()).isEqualTo("Ana");
        assertThat(recordRepository.findById(manual).orElseThrow().getManualFirstName()).isEqualTo("Carmen");
        assertThat(recordRepository.countBySessionId(s)).isEqualTo(2);
    }

    @Test
    void aDeactivatedTeacherCanNoLongerEditAddOrRemove() throws Exception {
        Teacher teacher = createTeacher("former@university.example.ro");
        Fixture f = fixtureFor(teacher);
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        AttendanceRecord real = insertRecord(f.sessionId(), ana);
        RequestPostProcessor former = teacherLogin("former@university.example.ro"); // role from an earlier sign-in
        long manual = addByName(f, "Carmen", "Dobre");

        teacher.deactivate();  // what the admin's "Deactivate" does (AdminTeachersTest covers the admin side)
        teacherRepository.saveAndFlush(teacher);

        mvc.perform(post("/sessions/{id}/records/{rid}/name", f.sessionId(), real.getId()).with(csrf()).with(former)
                .param("firstName", "Hacked").param("lastName", "Name")).andExpect(status().isForbidden());
        mvc.perform(post("/sessions/{id}/records", f.sessionId()).with(csrf()).with(former)
                .param("firstName", "Ion").param("lastName", "Dobre")).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/{id}/students", f.sessionId()).with(former)).andExpect(status().isForbidden());
        mvc.perform(delete("/sessions/{id}/records/{rid}", f.sessionId(), manual).with(csrf()).with(former))
                .andExpect(status().isForbidden());

        assertThat(findStudent("sub-ana").orElseThrow().getFirstName()).isEqualTo("Ana");
        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(2);
    }

    @Test
    void studentsCannotUseTheTeacherEndpoints() throws Exception {
        Fixture f = openFixture();
        AttendanceRecord record = insertRecord(f.sessionId(), insertStudent("sub-ana", "Ana", "Popescu"));
        RequestPostProcessor student = studentLogin("sub-ana", "Ana", "Popescu");

        mvc.perform(post("/sessions/{id}/records/{rid}/name", f.sessionId(), record.getId()).with(csrf())
                .with(student).param("firstName", "Anastasia").param("lastName", "Pop"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/sessions/{id}/records", f.sessionId()).with(csrf()).with(student)
                .param("firstName", "Friend").param("lastName", "Athome")).andExpect(status().isForbidden());

        assertThat(findStudent("sub-ana").orElseThrow().getFirstName()).isEqualTo("Ana");
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private RequestPostProcessor owner(Fixture f) {
        return teacherLogin(teacherRepository.findById(f.teacherId()).orElseThrow().getEmail());
    }

    private Fixture fixtureFor(Teacher teacher) {
        long courseId = courseService.createCourse(teacher.getId(), "Baze de date", null).getId();
        UUID sessionId = sessionService.openSession(teacher.getId(), courseId, 15, null).getId();
        return new Fixture(teacher.getId(), courseId, sessionId);
    }

    private ResultActions rename(Fixture f, long recordId, String first, String last) throws Exception {
        return mvc.perform(post("/sessions/{id}/records/{rid}/name", f.sessionId(), recordId)
                .with(csrf()).with(owner(f)).param("firstName", first).param("lastName", last));
    }

    private ResultActions addStudent(Fixture f, long studentId) throws Exception {
        return mvc.perform(post("/sessions/{id}/records", f.sessionId()).with(csrf()).with(owner(f))
                .param("studentId", String.valueOf(studentId)));
    }

    /** Adds a free-text manual entry and returns its record id. */
    private long addByName(Fixture f, String first, String last) throws Exception {
        MvcResult result = mvc.perform(post("/sessions/{id}/records", f.sessionId()).with(csrf()).with(owner(f))
                        .param("firstName", first).param("lastName", last))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.recordId")).longValue();
    }

    /** A real check-in: scan with a fresh token, then sign in and check in. */
    private ResultActions checkIn(Browser browser, UUID sessionId, RequestPostProcessor login, String first,
                                  String last) throws Exception {
        scan(browser, sessionId, validToken(sessionId)).andExpect(status().is2xxSuccessful());
        return postCheckIn(browser, sessionId, login, first, last);
    }

    private static String doneUrl(UUID sessionId) {
        return "/a/" + sessionId + "/done";
    }
}
