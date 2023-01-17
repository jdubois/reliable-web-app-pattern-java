package org.airsonic.player.security;

import org.airsonic.player.domain.User;
import org.airsonic.player.service.SecurityService;
import org.apache.commons.lang.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class AADAddAuthorizedUsersFilter extends OncePerRequestFilter {

    @Autowired
    SecurityService securityService;

    private static final Logger LOG = LoggerFactory.getLogger(AADAddAuthorizedUsersFilter.class);

    private static final List<String> APPROLES = new ArrayList<>(Arrays.asList("APPROLE_User", "APPROLE_Admin", "APPROLE_Creator"));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        LOG.debug("In the AADAddAuthorizedUsersFilter filter");

        // Add the user to the User database table if and only if they have a valid app role.
        if (isAirsonicUser(request)) {
            LOG.debug("user is an airsonic user");
            updateUser(request);
        }

        LOG.debug("AADAddAuthorizedUsersFilter calling doFilter");
        filterChain.doFilter(request, response);
    }

    private boolean isAirsonicUser(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (null == authentication) {
            return false;
        }

        Collection<? extends GrantedAuthority> grantedAuthorities = authentication.getAuthorities();
        if (null == grantedAuthorities) {
            return false;
        }

        List<String> authorities = grantedAuthorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        LOG.debug("User {} has granted authorities {}", authentication.getName(),
            authorities.stream().collect(Collectors.joining(", ")));

        boolean containsAirsonicRole = APPROLES.stream().anyMatch(authorities::contains);
        return containsAirsonicRole;
    }

    private void updateUser(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        final String userName = authentication.getName();

        User user = securityService.getUserByName(userName, true);

        final String authority = authentication.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(APPROLES::contains)
            .findFirst()
            .orElse("UNKNOWN");

        Set<User.Role> roles = new HashSet<>();

        if ("APPROLE_Admin".equals(authority)) {
            LOG.debug("Associating {} as an admin", userName);

            roles.add(User.Role.ADMIN);
            roles.add(User.Role.SETTINGS);
            roles.add(User.Role.DOWNLOAD);
            roles.add(User.Role.UPLOAD);
            roles.add(User.Role.PLAYLIST);
            roles.add(User.Role.COVERART);
            roles.add(User.Role.COMMENT);
            roles.add(User.Role.PODCAST);
            roles.add(User.Role.STREAM);
            roles.add(User.Role.JUKEBOX);
            roles.add(User.Role.SHARE);
        } else if ("APPROLE_User".equals(authority)) {
            LOG.debug("Associating {} as a user", userName);

            roles.add(User.Role.DOWNLOAD);
            roles.add(User.Role.STREAM);
        } else if ("APPROLE_Creator".equals(authority)) {
            LOG.debug("Associating {} as a creator", userName);

            roles.add(User.Role.DOWNLOAD);
            roles.add(User.Role.STREAM);
            roles.add(User.Role.UPLOAD);
            roles.add(User.Role.PLAYLIST);
            roles.add(User.Role.COVERART);
            roles.add(User.Role.COMMENT);
            roles.add(User.Role.PODCAST);
            roles.add(User.Role.SHARE);
        } else {
            LOG.debug("There are no roles associated with user {}", userName);
        }
        user.setRoles(roles);

        securityService.updateUser(user);
        LOG.debug("*** Updated user {}", userName);
    }

    private void addUserToDatabase(String userName) {
        LOG.debug("Verifying {} is in the user db table", userName);

        if (null != securityService.getUserByName(userName, true)) {
            LOG.debug("The user {} exists in the database", userName);
            return;
        }

        LOG.debug("The user {} does not exists in the database", userName);
        User user = new User(userName, null);
        final String password = RandomStringUtils.randomAlphanumeric(30);
        securityService.createUser(user, password, "Autogenerated user " + userName + " from Azure Active Directory");
        LOG.debug("Created user {}", userName);
    }
}
