package com.luffy.tradeflow_learning_service.web;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_learning_service.academy.AcademyService;
import com.luffy.tradeflow_learning_service.academy.QuizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Trading Academy for the signed-in user. Educational content only; not investment advice. */
@RestController
@RequestMapping("/api/academy")
@Tag(name = "Trading Academy")
public class AcademyController {

    public record SubmitRequest(@NotNull @Size(max = 50) List<QuizService.Answer> answers) {
    }

    private final AcademyService academy;
    private final QuizService quizzes;

    public AcademyController(AcademyService academy, QuizService quizzes) {
        this.academy = academy;
        this.quizzes = quizzes;
    }

    @GetMapping("/modules")
    @Operation(summary = "All modules with the caller's progress")
    public AcademyService.Overview modules(AuthenticatedUser user) {
        return academy.overview(user.id());
    }

    @GetMapping("/modules/{slug}")
    @Operation(summary = "One module with its lessons and quiz status")
    public AcademyService.ModuleSummary module(AuthenticatedUser user, @PathVariable String slug) {
        return academy.module(user.id(), slug);
    }

    @GetMapping("/modules/{slug}/lessons/{lesson}")
    @Operation(summary = "Lesson content (Markdown)")
    public AcademyService.LessonView lesson(AuthenticatedUser user, @PathVariable String slug, @PathVariable String lesson) {
        return academy.lesson(user.id(), slug, lesson);
    }

    @PostMapping("/modules/{slug}/lessons/{lesson}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark a lesson as read (idempotent)")
    public void complete(AuthenticatedUser user, @PathVariable String slug, @PathVariable String lesson) {
        academy.completeLesson(user.id(), slug, lesson);
    }

    @PostMapping("/modules/{slug}/quiz/attempts")
    @Operation(summary = "Start (or resume) the module quiz; questions are returned without answers")
    public QuizService.AttemptView startQuiz(AuthenticatedUser user, @PathVariable String slug) {
        return academy.startModuleQuiz(user.id(), slug);
    }

    @PostMapping("/attempts/{id}/submit")
    @Operation(summary = "Submit answers; graded on the server, with explanations")
    public QuizService.ResultView submit(AuthenticatedUser user, @PathVariable UUID id,
                                         @Valid @RequestBody SubmitRequest request) {
        return quizzes.submit(user.id(), id, request.answers());
    }

    @GetMapping("/attempts/{id}")
    @Operation(summary = "An open attempt (questions) or a submitted one (result)")
    public Object attempt(AuthenticatedUser user, @PathVariable UUID id) {
        return quizzes.get(user.id(), id);
    }
}
