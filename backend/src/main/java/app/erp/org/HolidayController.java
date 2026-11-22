package app.erp.org;

import app.erp.common.*;
import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/holidays")
public class HolidayController extends CrudSupport<Holiday> {
    public HolidayController(HolidayRepository repo, ObjectMapper mapper, AuditService audit) {
        super(Holiday.class, repo, mapper, audit, Perm.MASTER_READ, Perm.MASTER_WRITE);
    }
    @Override protected Set<String> filterable() { return Set.of("branchId", "holidayDate"); }
    @Override protected Sort defaultSort() { return Sort.by("holidayDate"); }
    @Override protected void validate(Holiday e, boolean isNew) {
        if (e.getHolidayDate() == null) throw ApiException.field("holidayDate", "Required");
        if (e.getName() == null) throw ApiException.field("name", "Required");
    }
}
