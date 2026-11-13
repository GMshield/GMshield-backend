package io.github.gmshield.shared.web;

import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.ProblemBody;
import io.github.gmshield.shared.service.TraceContext;
import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;

/**
 * Replaces the server's own HTML error page. A request that the server refuses before the
 * application sees it (for example a malformed request line) is answered in the same error shape
 * as everything else. Every value written here is a constant or a generated id: no escaping needed.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        ApiProblem problem = ApiProblem.forStatus(status);
        String body = "{\"type\":\"" + ProblemBody.TYPE_PREFIX + problem.code() + "\",\"title\":\"" + problem.title()
                + "\",\"status\":" + problem.status() + ",\"category\":\"" + problem.category() + "\",\"code\":\""
                + problem.code() + "\",\"params\":{},\"traceId\":\"" + TraceContext.newTraceId() + "\"}";
        try {
            response.setContentType(ProblemBody.MEDIA_TYPE);
            response.setCharacterEncoding("utf-8");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(body);
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException alreadyGone) {
            // The client has left or the answer has started: there is nobody to tell.
        }
    }
}
