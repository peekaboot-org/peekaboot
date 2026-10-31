package org.peekaboot.backend.problem;

/** Refuses a request to Peekaboot's API with {@link #problem()}, which {@link ApiProblemHandler} answers with. */
public final class ApiProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Never serialised: the exception lives for one request. */
    private final transient ApiProblem problem;

    public ApiProblemException(ApiProblem problem) {
        super(problem.detail());
        this.problem = problem;
    }

    /** Refuses the request for its {@code parameter}, with {@link ApiProblem#badParameter}. */
    public static ApiProblemException badParameter(String parameter, String detail) {
        return new ApiProblemException(ApiProblem.badParameter(parameter, detail));
    }

    public ApiProblem problem() {
        return problem;
    }
}
