package com.caiocodes.billing.shared.exception;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Tradutor único de exceção para HTTP. Nenhum controller tem try/catch.
 *
 * <p>O corpo segue a RFC 7807 ({@link ProblemDetail}, nativo do Spring 6),
 * em vez de um formato de erro inventado: cliente HTTP genérico, gateway e
 * ferramenta de observabilidade já sabem ler {@code application/problem+json}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI TIPO_BASE = URI.create("https://docs.billing.local/erros/");

    /** Erros de negócio: previstos, logados em WARN, mensagem vai para o cliente. */
    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException ex) {
        log.warn("Regra de negócio violada [{}]: {}", ex.getCode(), ex.getMessage());
        return montar(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    /**
     * Rede de segurança das constraints do banco. Quando duas requisições
     * simultâneas passam pela verificação do service, é o índice único que
     * decide — e a violação chega aqui para virar 409 em vez de 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegridade(DataIntegrityViolationException ex) {
        String constraint = extrairConstraint(ex);
        log.warn("Violação de integridade no banco (constraint={})", constraint);
        return montar(HttpStatus.CONFLICT, "CONFLITO_DE_INTEGRIDADE",
                "A operação conflita com um registro existente"
                        + (constraint != null ? " (%s)".formatted(constraint) : "."));
    }

    /**
     * {@code ?sort=campoQueNaoExiste}. Sem este handler o Spring Data deixa a
     * exceção subir e o cliente recebe 500 por um erro que é dele.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleOrdenacaoInvalida(PropertyReferenceException ex) {
        return montar(HttpStatus.BAD_REQUEST, "ORDENACAO_INVALIDA",
                "Não é possível ordenar por '%s': o campo não existe."
                        .formatted(ex.getPropertyName()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAcessoNegado(AccessDeniedException ex) {
        return montar(HttpStatus.FORBIDDEN, "ACESSO_NEGADO",
                "Seu perfil não permite executar esta operação.");
    }

    /** Rede final. Nunca vaza stacktrace nem mensagem interna para o cliente. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleInesperado(Exception ex) {
        log.error("Erro não tratado", ex);
        return montar(HttpStatus.INTERNAL_SERVER_ERROR, "ERRO_INTERNO",
                "Erro interno. Se persistir, informe o campo traceId ao suporte.");
    }

    /** Bean Validation: 422 com a lista campo a campo, não um texto corrido. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<ErroDeCampo> campos = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> campos.add(new ErroDeCampo(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> campos.add(new ErroDeCampo(e.getObjectName(), e.getDefaultMessage())));

        ProblemDetail problema = montar(HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDACAO_FALHOU", "Um ou mais campos estão inválidos.");
        problema.setProperty("errors", campos);
        return ResponseEntity.unprocessableEntity().body(problema);
    }

    private ProblemDetail montar(HttpStatusCode status, String code, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setType(TIPO_BASE.resolve(code.toLowerCase().replace('_', '-')));
        problema.setProperty("code", code);
        problema.setProperty("timestamp", OffsetDateTime.now());
        return problema;
    }

    /**
     * Nome da constraint violada.
     *
     * <p>Lido da exceção do Hibernate, e não da {@code PSQLException} do driver:
     * o driver está em escopo {@code runtime} de propósito — nada do código de
     * aplicação deve compilar contra um banco específico.
     */
    private String extrairConstraint(DataIntegrityViolationException ex) {
        Throwable causa = ex.getCause();
        if (causa instanceof org.hibernate.exception.ConstraintViolationException hibernate) {
            return hibernate.getConstraintName();
        }
        return null;
    }

    public record ErroDeCampo(String field, String message) {}
}
