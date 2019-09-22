package com.dataman.gitstats.service;


import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.dataman.gitstats.exception.ApiResultCode;
import com.dataman.gitstats.exception.BusinessException;
import com.dataman.gitstats.param.ProjectWithBranches;
import com.dataman.gitstats.po.*;
import com.dataman.gitstats.repository.*;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApi.ApiVersion;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.Pager;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.webhook.EventCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Component;

import com.dataman.gitstats.util.ClassUitl;
import com.dataman.gitstats.util.GitlabUtil;
import com.dataman.gitstats.vo.CommitStatsVo;


@Component
public class AsyncTask {

	protected Logger logger = LoggerFactory.getLogger(this.getClass());
	
	@Autowired
	GitlabUtil gitlabUtil;
	
	@Autowired
	ProjectBranchStatsRepository projectBranchStatsRepository;

	@Autowired
	CommitStatsRepository commitStatsRepository;

	@Autowired
	PushEventRecordRepository pushEventRecordRepository;

	// FIXME: 启动报错，暂时注释掉！
	// @Autowired
	private MergeRequestEventRecordRepository mergeRequestEventRecordRepository;

	@Autowired
	private StatsCommitAsyncTask statsCommitAsyncTask;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private GroupStatsRepository groupStatsRepository;


	@Async
	public void initGroupStats(GroupStats groupStats) throws Exception{
		logger.info("初始化开始:"+groupStats.getWebUrl());
		Calendar cal =Calendar.getInstance();
		long begin = System.currentTimeMillis();
		int groupAddRow=0,groupRemoveRow=0,groupTotalCommits=0;
		ScheduledThreadPoolExecutor pool=new ScheduledThreadPoolExecutor(9);
		GitLabApi gitLabApi=  gitlabUtil.getGitLabApi(groupStats.getAccountid());
		List<ProjectBranchStats> targetProjectBranchStatsList=new ArrayList<ProjectBranchStats>();
		List<Project> allProjects= gitLabApi.getGroupApi().getProjects(groupStats.getGroupId());
		if(groupStats.getInclude()!=null){
			for(ProjectWithBranches projectWithBranchs:groupStats.getInclude()){
				Project project=gitLabApi.getProjectApi().getProject(groupStats.getFullPath(),projectWithBranchs.getName());
				ProjectStats projectStats=initProjectStats(groupStats,project);
				if(projectWithBranchs.getBranches()!=null){//include中包含分支列表则只初始化分支列表
					for(String branchName:projectWithBranchs.getBranches()){
						Branch branch=gitLabApi.getRepositoryApi().getBranch(project.getId(),branchName);
						ProjectBranchStats projectBranchStats=initProjectBranchStats(groupStats,projectStats,branch);
						targetProjectBranchStatsList.add(projectBranchStats);
					}
				}else{//include中不包含分支列表则初始化所有分支，exclude中包含的除外
					if(groupStats.getExclude()!=null&&groupStats.getExclude().stream().anyMatch(projectWithBranches -> projectWithBranches.getName().equals(projectWithBranchs.getName()))){
						List<String> excludeBranches=groupStats.getExclude().stream().filter(projectWithBranches -> projectWithBranches.getName().equals(projectWithBranchs.getName()))
								.flatMap(map -> map.getBranches().stream()).collect(Collectors.toList());
						List<Branch> allBranches=gitLabApi.getRepositoryApi().getBranches(project.getId());
						for(Branch branch:allBranches){
							if(excludeBranches.contains(branch.getName())){
								continue;
							}
							ProjectBranchStats projectBranchStats=initProjectBranchStats(groupStats,projectStats,branch);
							targetProjectBranchStatsList.add(projectBranchStats);
						}
					}

				}

			}
		}else if(groupStats.getExclude()!=null){//include 为空，exclude不为空
			for(Project project:allProjects){
				ProjectStats projectStats=initProjectStats(groupStats,project);
				List<String> excludeProjects=groupStats.getExclude().stream().map(projectWithBranches -> projectWithBranches.getName()).collect(Collectors.toList());
				if(excludeProjects.contains(project.getName())){
					List<String> excludeBranches=groupStats.getExclude().stream().filter(projectWithBranches -> projectWithBranches.getName().equals(project.getName()))
							.flatMap(map -> map.getBranches().stream()).collect(Collectors.toList());
					if(excludeBranches!=null&&excludeBranches.size()>0){
						List<Branch> allBranches=gitLabApi.getRepositoryApi().getBranches(project.getId());
						for(Branch branch:allBranches){
							if(excludeBranches.contains(branch.getName())){
								continue;
							}
							ProjectBranchStats projectBranchStats=initProjectBranchStats(groupStats,projectStats,branch);
							targetProjectBranchStatsList.add(projectBranchStats);
						}
					}else{
						continue;
					}

				}else{
					List<Branch> allBranches=gitLabApi.getRepositoryApi().getBranches(project.getId());
					for(Branch branch:allBranches){
						ProjectBranchStats projectBranchStats=initProjectBranchStats(groupStats,projectStats,branch);
						targetProjectBranchStatsList.add(projectBranchStats);
					}
				}
			}
		}else{//include 、exclude都为空
			for(Project project:allProjects){
				ProjectStats projectStats=initProjectStats(groupStats,project);
				List<Branch> allBranches=gitLabApi.getRepositoryApi().getBranches(project.getId());
				for(Branch branch:allBranches){
					ProjectBranchStats projectBranchStats=initProjectBranchStats(groupStats,projectStats,branch);
					targetProjectBranchStatsList.add(projectBranchStats);
				}
			}
		}
		try{
			CountDownLatch cdl=new CountDownLatch(targetProjectBranchStatsList.size());
			for(ProjectBranchStats projectBranchStats:targetProjectBranchStatsList){
				pool.execute(new Runnable() {
					@Override
					public void run() {
						try {
							initProjectStats2(projectBranchStats,cdl);
						} catch (Exception e) {
							logger.error("初始化出错：",e);
							GroupStats groupStats2=groupStatsRepository.findById(groupStats.getId()).get();
							groupStats2.setStatus(-1);
							groupStats2.setLastupdate(new Date());
							groupStatsRepository.save(groupStats2);
							pool.shutdownNow();
						}
					}
				});
			}
			cdl.await();
			GroupStats groupStats2=groupStatsRepository.findById(groupStats.getId()).get();
			groupStats2.setStatus(1);
			groupStats2.setLastupdate(new Date());
			groupStatsRepository.save(groupStats2);
			long end=System.currentTimeMillis();
			logger.info(groupStats2.getName()+"初始化完成，耗时"+(end-begin)+"ms");
		}catch (Exception e){
			logger.error("初始化出错：",e);
			GroupStats groupStats2=groupStatsRepository.findById(groupStats.getId()).get();
			groupStats2.setStatus(-1);
			groupStats2.setLastupdate(new Date());
			groupStatsRepository.save(groupStats2);
			pool.shutdownNow();
		}finally {
			pool.shutdown();
		}

	}

	private ProjectBranchStats initProjectBranchStats(GroupStats groupStats,ProjectStats projectStats,Branch branch){
		ProjectBranchStats projectBranchStats=new ProjectBranchStats();
		projectBranchStats.setId(projectStats.getId()+"_"+projectBranchStats.getProid()+"_"+branch);
		projectBranchStats.setGroupId(groupStats.getId());
		projectBranchStats.setProjectId(projectStats.getId());
		projectBranchStats.setWeburl(projectStats.getWeburl());
		projectBranchStats.setBranch(branch.getName());
		projectBranchStats.setStatus(0);
		projectBranchStats.setLastupdate(new Date());
		projectBranchStats.setCreatedate(projectStats.getCreatedAt());
		projectBranchStats.setProjectNameWithNamespace(projectStats.getProjectNameWithNamespace());
		projectBranchStats.setAccountid(groupStats.getAccountid());
		projectBranchStats.setCreatedAt(projectStats.getCreatedAt());
		projectBranchStats.setProid(projectStats.getProid());
		projectBranchStats=projectBranchStatsRepository.insert(projectBranchStats);
		return projectBranchStats;
	}

	private ProjectStats initProjectStats(GroupStats groupStats,Project project){
		ProjectStats projectStats=new ProjectStats();
		projectStats.setId(groupStats.getId()+"_"+project.getId());
		projectStats.setProid(project.getId());
		projectStats.setStatus(0);
		projectStats.setProjectNameWithNamespace(project.getNameWithNamespace());
		projectStats.setCreatedAt(project.getCreatedAt());
		projectStats.setAccountid(groupStats.getAccountid());
		projectStats.setCreatedate(new Date());
		projectStats.setGroupId(groupStats.getId());
		projectStats.setLastupdate(new Date());
		projectStats.setWeburl(project.getWebUrl());
		projectStats.setCreatedate(new Date());
		projectRepository.insert(projectStats);
		return projectStats;
	}

	/**
	 * @method initProjectStats(初始化数据)
	 * @return String
	 * @author liuqing
	 * @throws GitLabApiException 
	 * @throws Exception 
	 * @date 2017年9月19日 下午4:31:20
	 */
	@Async
	public Future<String> initProjectStats(ProjectBranchStats pbs) throws GitLabApiException{
		logger.info("初始化开始:"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch());
		Calendar cal =Calendar.getInstance();
		long begin = System.currentTimeMillis();
		int addRow=0,removeRow=0,totalCommits=0;
		int projectId= pbs.getProid();
		String branch=pbs.getBranch();
		ScheduledThreadPoolExecutor pool=new ScheduledThreadPoolExecutor(9);
		statsCommitAsyncTask.clearPageNum(pbs.getId());
		try {
			// 清理数据
			GitLabApi gitLabApi=  gitlabUtil.getGitLabApi(pbs.getAccountid());
			//获取当前项目当前分支的所有commit
			if(gitLabApi.getApiVersion() == ApiVersion.V4){
				//分页获取 (每页获取 100个数据)
				//TODO	这里的第一页数据查询出来没有入库？
				Pager<Commit> page= gitLabApi.getCommitsApi().getCommits(projectId, branch, null, cal.getTime(),100);
				logger.info(pbs.getProjectNameWithNamespace()+"."+pbs.getBranch()+":TotalPages:"+page.getTotalPages());
				CountDownLatch cdl=new CountDownLatch(page.getTotalPages());
				List<Future<CommitStatsVo>> stats=new ArrayList<>();
				//异步读取分页信息
				while (page.hasNext()) {
					pool.execute(new Runnable() {
						@Override
						public void run() {
							List<Commit> list=  page.next();
							Future<CommitStatsVo> f= null;
							try {
								f = statsCommitAsyncTask.commitstats(list, gitLabApi, projectId, pbs.getId(), page.getCurrentPage(), cdl,null);
								stats.add(f);
							} catch (Exception e) {
								logger.info("初始化失败:"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch());
								logger.error("失败原因：版本号V4：",e);
								pbs.setStatus(-1);
								projectBranchStatsRepository.save(pbs);
								pool.shutdownNow();
							}
						}
					});
				}
				// 计数机阻塞 返回结果
				cdl.await();
				// 统计 每页 返回的结果
				for (Future<CommitStatsVo> future : stats) {
						CommitStatsVo vo= future.get();
						addRow+=vo.getAddrow();
						removeRow+=vo.getRemoverow();
					    totalCommits+=vo.getCommit();
				}
				pbs.setStatus(1);
				pbs.setTotalAddRow(addRow);
				pbs.setTotalDelRow(removeRow);
				pbs.setTotalRow(addRow-removeRow);
				pbs.setLastupdate(cal.getTime());
				projectBranchStatsRepository.save(pbs);  //保存跟新记录
				logger.info("update success");
				long usetime = begin-System.currentTimeMillis();
				logger.info("初始化"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch()+"完成耗时:"+usetime+"ms");
			}else if(gitLabApi.getApiVersion() == ApiVersion.V3){
				Integer pageNum=0;
				boolean hasNext=true;
				V3StatsCallback v3back=new V3StatsCallback(pbs,projectBranchStatsRepository,begin);
				while (hasNext) {
					List<Commit> list= gitLabApi.getCommitsApi().getCommits(projectId,branch,null,null,pageNum,100);
					if(list.isEmpty()){
						hasNext=false;
					}else{
						v3back.addPages();
						pool.execute(new Runnable() {
							@Override
							public void run() {
								try {
									statsCommitAsyncTask.commitstats(list, gitLabApi, projectId, pbs.getId(), 0, null,v3back);
								} catch (Exception e) {
									logger.info("**********************************初始化失败:"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch());
									logger.error("失败原因：版本号V3：",e);
									pbs.setStatus(-1);
									projectBranchStatsRepository.save(pbs);
									pool.shutdownNow();
								}
							}
						});
					}
					pageNum++;
				}
			}
		} catch (Exception e) {
			logger.info("初始化失败:"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch());
			logger.error("失败原因:",e);
			pbs.setStatus(-1);
			projectBranchStatsRepository.save(pbs);
			pool.shutdownNow();
		}finally {
			if(!pool.isTerminating()){
				pool.shutdown();
			}
		}
		return new AsyncResult<String>("初始化完成");  
	}

	/**
	 * @method: updateGroup
	 * @Description 级联更新group内容
	 * @author biancl
	 * @date 2018-04-10 19:25
	 * @param
	 * @return 
	 */
	private synchronized void updateGroup(ProjectBranchStats pbs) throws InterruptedException {
		ProjectStats projectStats=projectRepository.findById(pbs.getProjectId()).get();
		projectStats.setTotalAddRow(projectStats.getTotalAddRow()+pbs.getTotalAddRow());
		projectStats.setTotalDelRow(projectStats.getTotalDelRow()+pbs.getTotalDelRow());
		projectStats.setTotalRow(projectStats.getTotalRow()+pbs.getTotalRow());
		projectStats.setTotalCommits(projectStats.getTotalCommits()+pbs.getTotalCommits());
		projectRepository.save(projectStats);
		GroupStats groupStats=groupStatsRepository.findById(pbs.getGroupId()).get();
		groupStats.setTotalAddRow(groupStats.getTotalAddRow()+pbs.getTotalAddRow());
		groupStats.setTotalDelRow(groupStats.getTotalDelRow()+pbs.getTotalDelRow());
		groupStats.setTotalRow(groupStats.getTotalRow()+pbs.getTotalRow());
		groupStats.setTotalCommits(groupStats.getTotalCommits()+pbs.getTotalCommits());
		groupStatsRepository.save(groupStats);
	}

	/**
	 * @method: updateGroupForEvents
	 * @Description hook push event和merge event级联更新group和project信息
	 * @author biancl
	 * @date 2018-04-18 15:09
	 * @param
	 * @param totalAdd
	 *@param totalDel
	 * @param size @return
	 */
	private synchronized void updateGroupForEvents(ProjectBranchStats pbs, int totalAdd, int totalDel, int totalCommits) throws InterruptedException {
		ProjectStats projectStats=projectRepository.findById(pbs.getProjectId()).get();
		projectStats.setTotalAddRow(projectStats.getTotalAddRow()+totalAdd);
		projectStats.setTotalDelRow(projectStats.getTotalDelRow()+totalDel);
		projectStats.setTotalRow(projectStats.getTotalRow()+totalAdd-totalDel);
		projectStats.setTotalCommits(projectStats.getTotalCommits()+totalCommits);
		projectRepository.save(projectStats);
		GroupStats groupStats=groupStatsRepository.findById(pbs.getGroupId()).get();
		groupStats.setTotalAddRow(groupStats.getTotalAddRow()+totalAdd);
		groupStats.setTotalDelRow(groupStats.getTotalDelRow()+totalDel);
		groupStats.setTotalRow(groupStats.getTotalRow()+totalAdd-totalDel);
		groupStats.setTotalCommits(groupStats.getTotalCommits()+totalCommits);
		groupStatsRepository.save(groupStats);
	}

	/**
	 * 不使用多线程并发请求gitlab
	 * @param pbs
	 * @param cdl
	 * @return
	 * @throws
	 */
	@Async
	public void initProjectStats2(ProjectBranchStats pbs, CountDownLatch cdl) throws Exception{
		logger.info("初始化开始:"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch());
		Calendar cal =Calendar.getInstance();
		long begin = System.currentTimeMillis();
		int addRow=0,removeRow=0;int totalCommits=0;
		int projectId= pbs.getProid();
		String branch=pbs.getBranch();
		try {
			// 清理数据
			GitLabApi gitLabApi=  gitlabUtil.getGitLabApi(pbs.getAccountid());
			//获取当前项目当前分支的所有commit
			if(gitLabApi.getApiVersion() == ApiVersion.V4){
				//分页获取 (每页获取 100个数据)
				//TODO	这里的第一页数据查询出来没有入库？
				Pager<Commit> page= gitLabApi.getCommitsApi().getCommits(projectId, branch, null, cal.getTime(),100);
				logger.info(pbs.getProjectNameWithNamespace()+"."+pbs.getBranch()+":TotalPages:"+page.getTotalPages());
				List<CommitStatsVo> stats=new ArrayList<>();
				//异步读取分页信息
				while (page.hasNext()) {
					List<Commit> list=  page.next();
					CommitStatsVo f= statsCommitAsyncTask.commitstats2(list, gitLabApi, projectId, pbs.getId(),pbs.getGroupId(), page.getCurrentPage());
					stats.add(f);
				}
				// 计数机阻塞 返回结果
				// 统计 每页 返回的结果
				for (CommitStatsVo vo : stats) {
					addRow+=vo.getAddrow();
					removeRow+=vo.getRemoverow();
					totalCommits+=vo.getCommit();
				}
				pbs.setStatus(2);
				pbs.setTotalAddRow(addRow);
				pbs.setTotalDelRow(removeRow);
				pbs.setTotalRow(addRow-removeRow);
				pbs.setLastupdate(cal.getTime());
				pbs.setTotalCommits(totalCommits);
				projectBranchStatsRepository.save(pbs);  //保存跟新记录
				logger.info("update success");
				long usetime = begin-System.currentTimeMillis();
				logger.info("初始化"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch()+"完成耗时:"+usetime+"ms");
			}else if(gitLabApi.getApiVersion() == ApiVersion.V3){
				Integer pageNum=0;
				boolean hasNext=true;
				List<CommitStatsVo> commits=new ArrayList<CommitStatsVo>();
				while (hasNext) {
					List<Commit> list= gitLabApi.getCommitsApi().getCommits(projectId,branch,null,null,pageNum,100);
					if(list.isEmpty()){
						hasNext=false;
					}else{
						commits.add(statsCommitAsyncTask.commitstats2(list, gitLabApi, projectId, pbs.getId(),pbs.getGroupId(), pageNum + 1));
					}
					pageNum++;
				}
				for (CommitStatsVo vo : commits) {
					addRow+=vo.getAddrow();
					removeRow+=vo.getRemoverow();
					totalCommits+=vo.getCommit();
				}
				pbs.setStatus(2);
				pbs.setTotalAddRow(addRow);
				pbs.setTotalDelRow(removeRow);
				pbs.setTotalRow(addRow-removeRow);
				pbs.setLastupdate(cal.getTime());
				pbs.setTotalCommits(totalCommits);
				projectBranchStatsRepository.save(pbs);
				updateGroup(pbs);
				long usetime = begin-System.currentTimeMillis();
				logger.info("初始化"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch()+"完成耗时:"+usetime+"ms");
			}
		} catch (Exception e) {
			logger.info("初始化失败:"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch());
			logger.error("失败原因:",e);
			pbs.setStatus(-1);
			projectBranchStatsRepository.save(pbs);
			throw new BusinessException(ApiResultCode.ERR_1001);
		}
		if (null != cdl) {
			cdl.countDown();
		}
	}


	@Async
	public void saveCommitStatsFromPushEventCommitsList(PushEventRecord record,ProjectBranchStats projectBranchStats,List<EventCommit> eventCommitList) throws Exception {
		GitLabApi gitLabApi=gitlabUtil.getGitLabApi(projectBranchStats.getAccountid());
			while (projectBranchStats.getStatus()==0){
				Thread.sleep(1000);
				projectBranchStats=projectBranchStatsRepository.findById(projectBranchStats.getId()).get();
			}
		projectBranchStats.setStatus(0);
		projectBranchStatsRepository.save(projectBranchStats);
		CommitStatsPo commitStats;
		int totalAdd=0;
		int totalDel=0;
		for(EventCommit eventCommit:eventCommitList){
			Commit commit=gitLabApi.getCommitsApi().getCommit(projectBranchStats.getProid(),eventCommit.getId());
			commitStats=new CommitStatsPo();
			ClassUitl.copyPropertiesExclude(commit, commitStats, new String[]{"parentIds","stats"});
			// Set<String> branch=new HashSet<>();
			// branch.add(projectBranchStats.getBranch());
			commitStats.set_id(projectBranchStats.getGroupId() + "_" + commit.getId());
			commitStats.setBranchId(projectBranchStats.getId());
			commitStats.setGroupId(projectBranchStats.getGroupId());
			commitStats.setAddRow(commit.getStats().getAdditions());
			commitStats.setRemoveRow(commit.getStats().getDeletions());
			commitStats.setCrateDate(new Date());
			commitStatsRepository.save(commitStats);
			totalAdd+=commitStats.getAddRow();
			totalDel+=commitStats.getRemoveRow();
		}
		projectBranchStats.setTotalAddRow(projectBranchStats.getTotalAddRow()+totalAdd);
		projectBranchStats.setTotalDelRow(projectBranchStats.getTotalDelRow()+totalDel);
		projectBranchStats.setTotalRow(projectBranchStats.getTotalAddRow()-projectBranchStats.getTotalDelRow());
		projectBranchStats.setTotalCommits(projectBranchStats.getTotalCommits()+eventCommitList.size());
		updateGroupForEvents(projectBranchStats,totalAdd,totalDel,eventCommitList.size());
		projectBranchStats.setStatus(1);
		projectBranchStatsRepository.save(projectBranchStats);
		record.setStatus(MergeRequestEventRecord.FINISHED);
		record.setUpdateAt(new Date());
		pushEventRecordRepository.save(record);
	}

	@Async
	public void saveCommitStatsFromMergeRequestEventCommitsList(MergeRequestEventRecord record,ProjectBranchStats projectBranchStats,List<Commit> eventCommitList) throws Exception {
		GitLabApi gitLabApi=gitlabUtil.getGitLabApi(projectBranchStats.getAccountid());
		while (projectBranchStats.getStatus()==0){
			Thread.sleep(1000);
			projectBranchStats=projectBranchStatsRepository.findById(projectBranchStats.getId()).get();
		}
		projectBranchStats.setStatus(0);
		projectBranchStatsRepository.save(projectBranchStats);
		CommitStatsPo commitStats;
		int totalAdd=0;
		int totalDel=0;
		for(Commit eventCommit:eventCommitList){
			commitStats=commitStatsRepository.findById(eventCommit.getId()).get();
			Commit commit=gitLabApi.getCommitsApi().getCommit(projectBranchStats.getProid(),eventCommit.getId());
			commitStats=new CommitStatsPo();
			ClassUitl.copyPropertiesExclude(commit, commitStats, new String[]{"parentIds","stats"});
			// Set<String> branch=new HashSet<>();
			// branch.add(projectBranchStats.getBranch());
			commitStats.set_id(projectBranchStats.getGroupId()+"_"+commit.getId());
			commitStats.setBranchId(projectBranchStats.getId());
			commitStats.setAddRow(commit.getStats().getAdditions());
			commitStats.setRemoveRow(commit.getStats().getDeletions());
			commitStats.setGroupId(projectBranchStats.getGroupId());
			commitStats.setCrateDate(new Date());
			commitStatsRepository.save(commitStats);
			totalAdd+=commitStats.getAddRow();
			totalDel+=commitStats.getRemoveRow();
		}
		projectBranchStats.setTotalAddRow(projectBranchStats.getTotalAddRow()+totalAdd);
		projectBranchStats.setTotalDelRow(projectBranchStats.getTotalDelRow()+totalDel);
		projectBranchStats.setTotalRow(projectBranchStats.getTotalAddRow()-projectBranchStats.getTotalDelRow());
		projectBranchStats.setTotalCommits(projectBranchStats.getTotalCommits() + eventCommitList.size());
		updateGroupForEvents(projectBranchStats, totalAdd, totalDel, eventCommitList.size());
		projectBranchStats.setStatus(1);
		projectBranchStatsRepository.save(projectBranchStats);
		record.setStatus(MergeRequestEventRecord.FINISHED);
		record.setUpdateAt(new Date());
		mergeRequestEventRecordRepository.save(record);
	}
	
	public class V3StatsCallback {
		
		int addRow;
		int removeRow;
		int pages;
		int total;
		int addpage;
		long begin;
		ProjectBranchStatsRepository projectBranchStatsRepository;
		ProjectBranchStats pbs;
		protected Logger logger = LoggerFactory.getLogger(this.getClass());
		
		public V3StatsCallback(ProjectBranchStats pbs,ProjectBranchStatsRepository projectBranchStatsRepository,long begin){
			this.pbs=pbs;
			this.projectBranchStatsRepository=projectBranchStatsRepository;
			this.begin=begin;
		}
		
		public void setPages(int pages){
			this.pages=pages;
		}
		public void addPages(){
			this.pages=pages+1;
		}
		
		public synchronized void call(int add,int remove,int page,int size){
			this.addRow +=add;
			this.removeRow +=remove;
			this.total +=size;
			this.addpage ++;
			logger.info("第"+page+"页callback处理,处理进度("+addpage+"/"+pages+")");
			if(pages==addpage){
				Calendar cal=Calendar.getInstance();
				pbs.setStatus(1);
				pbs.setTotalAddRow(addRow);
				pbs.setTotalDelRow(removeRow);
				pbs.setTotalRow(addRow-removeRow);
				pbs.setTotalCommits(total);
				pbs.setLastupdate(cal.getTime());
				projectBranchStatsRepository.save(pbs);  //保存跟新记录
				logger.info("update success");
				long usetime = begin-System.currentTimeMillis();
				logger.info("初始化"+pbs.getProjectNameWithNamespace()+"."+pbs.getBranch()+"完成耗时:"+usetime+"ms");
				logger.info("total:"+total+"\tpages:"+addpage);
			}
		}
	}
	
	
}
