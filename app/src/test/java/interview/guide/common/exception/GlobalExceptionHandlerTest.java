package interview.guide.common.exception;

import interview.guide.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("should return 409 for agent turn conflict")
    void shouldReturnConflictStatusForAgentTurnConflict() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
            new BusinessException(ErrorCode.AGENT_TURN_CONFLICT, "当前会话已有运行中的 turn")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.AGENT_TURN_CONFLICT.getCode());
    }

    @Test
    @DisplayName("should return 404 for agent resources that do not exist")
    void shouldReturnNotFoundStatusForMissingAgentResource() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
            new BusinessException(ErrorCode.AGENT_TURN_NOT_FOUND, "未找到 Agent turn: turn-404")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.AGENT_TURN_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("should keep generic business failures on http 200")
    void shouldKeepGenericBusinessFailuresOnHttp200() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
            new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "agent failed")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.AGENT_EXECUTION_FAILED.getCode());
    }
}
