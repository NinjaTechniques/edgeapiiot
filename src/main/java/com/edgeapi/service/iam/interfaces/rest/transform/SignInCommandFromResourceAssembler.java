package com.edgeapi.service.iam.interfaces.rest.transform;

import com.edgeapi.service.iam.domain.model.commands.SignInCommand;
import com.edgeapi.service.iam.interfaces.rest.resources.SignInResource;

public class SignInCommandFromResourceAssembler {
    public static SignInCommand toCommandFromResource(SignInResource signInResource) {
        return new SignInCommand(signInResource.macAddress(), signInResource.secret());
    }
}
