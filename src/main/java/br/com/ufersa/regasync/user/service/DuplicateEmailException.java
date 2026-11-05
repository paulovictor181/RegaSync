package br.com.ufersa.regasync.user.service;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("Já existe um usuário com este e-mail.");
    }
}
