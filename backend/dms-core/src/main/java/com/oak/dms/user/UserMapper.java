package com.oak.dms.user;

import com.oak.dms.user.dto.UserResponse;
import com.oak.dms.user.User;
import org.mapstruct.Mapper;

/**
 * MapStruct entity/DTO mapping for {@link User}.
 */
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserResponse toResponse(User user);
}
