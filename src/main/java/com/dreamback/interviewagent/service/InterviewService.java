package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.CreateInterviewRequest;
import com.dreamback.interviewagent.dto.InterviewDetailResponse;
import com.dreamback.interviewagent.dto.InterviewSummaryResponse;
import com.dreamback.interviewagent.entity.InterviewQuestion;
import com.dreamback.interviewagent.entity.InterviewRecord;
import com.dreamback.interviewagent.repository.InterviewRecordRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewRecordRepository recordRepository;

    @Transactional
    public InterviewDetailResponse create(CreateInterviewRequest req) {
        InterviewRecord record = new InterviewRecord();
        record.setCompany(req.getCompany());
        record.setPosition(req.getPosition());
        record.setInterviewDate(req.getInterviewDate());
        record.setJd(req.getJd());
        record.setResult(req.getResult());
        record.setNotes(req.getNotes());

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
        return recordRepository.findAll().stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public InterviewDetailResponse get(Long id) {
        InterviewRecord record = recordRepository.findByIdWithQuestions(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "面试记录不存在: " + id));
        return toDetail(record);
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
