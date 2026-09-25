package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.CreateInterviewRequest;
import com.dreamback.interviewagent.dto.InterviewDetailResponse;
import com.dreamback.interviewagent.dto.InterviewSummaryResponse;
import com.dreamback.interviewagent.entity.InterviewQuestion;
import com.dreamback.interviewagent.entity.InterviewRecord;
import com.dreamback.interviewagent.repository.InterviewRecordRepository;
import com.dreamback.interviewagent.security.UserContext;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewRecordRepository recordRepository;
    private final UserContext userContext;

    @Transactional
    public InterviewDetailResponse create(CreateInterviewRequest req) {
        InterviewRecord record = new InterviewRecord();
        record.setCompany(req.getCompany());
        record.setPosition(req.getPosition());
        record.setInterviewDate(req.getInterviewDate());
        record.setJd(req.getJd());
        record.setResult(req.getResult());
        record.setNotes(req.getNotes());
        // 免鉴权模式（app.security.enabled=false）下为 null，退化为单用户共享
        record.setUserId(userContext.currentUserId().orElse(null));

        if (req.getQuestions() != null) {
            for (CreateInterviewRequest.QuestionInput in : req.getQuestions()) {
                InterviewQuestion q = new InterviewQuestion();
                q.setQuestion(in.getQuestion());
                q.setMyAnswer(in.getMyAnswer());
                q.setFeedback(in.getFeedback());
                q.setScore(in.getScore());
                q.setTags(in.getTags());
                record.addQuestion(q);
            }
        }
        InterviewRecord saved = recordRepository.save(record);
        return toDetail(saved);
    }

    @Transactional(readOnly = true)
    public List<InterviewSummaryResponse> list() {
        Long uid = userContext.currentUserId().orElse(null);
        var records = uid == null
                ? findAllLegacy()
                : recordRepository.findAllByUserIdOrderByCreatedAtDesc(uid);
        return records.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public InterviewDetailResponse get(Long id) {
        Long uid = userContext.currentUserId().orElse(null);
        InterviewRecord record = (uid == null
                ? findByIdLegacy(id)
                : recordRepository.findByIdWithQuestionsAndUserId(id, uid))
                // 越权访问也返回 404：不泄露"这条数据存在但你没权限"
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "面试记录不存在: " + id));
        return toDetail(record);
    }

    /** 免鉴权模式：查全部（单用户自用场景） */
    private Collection<InterviewRecord> findAllLegacy() {
        return recordRepository.findAll();
    }

    private Optional<InterviewRecord> findByIdLegacy(Long id) {
        return recordRepository.findById(id);
    }

    private InterviewSummaryResponse toSummary(InterviewRecord r) {
        InterviewSummaryResponse dto = new InterviewSummaryResponse();
        dto.setId(r.getId());
        dto.setCompany(r.getCompany());
        dto.setPosition(r.getPosition());
        dto.setInterviewDate(r.getInterviewDate());
        dto.setResult(r.getResult());
        dto.setQuestionCount(r.getQuestions() == null ? 0 : r.getQuestions().size());
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }

    public InterviewDetailResponse toDetail(InterviewRecord r) {
        InterviewDetailResponse dto = new InterviewDetailResponse();
        dto.setId(r.getId());
        dto.setCompany(r.getCompany());
        dto.setPosition(r.getPosition());
        dto.setInterviewDate(r.getInterviewDate());
        dto.setJd(r.getJd());
        dto.setResult(r.getResult());
        dto.setNotes(r.getNotes());
        dto.setCreatedAt(r.getCreatedAt());
        if (r.getQuestions() != null) {
            for (InterviewQuestion q : r.getQuestions()) {
                InterviewDetailResponse.QuestionView v = new InterviewDetailResponse.QuestionView();
                v.setId(q.getId());
                v.setQuestion(q.getQuestion());
                v.setMyAnswer(q.getMyAnswer());
                v.setFeedback(q.getFeedback());
                v.setScore(q.getScore());
                v.setTags(q.getTags());
                v.setWeakPoints(q.getWeakPoints());
                dto.getQuestions().add(v);
            }
        }
        return dto;
    }
}
